package org.sitare.quiz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sitare.quiz.attempt.AttemptService;
import org.sitare.quiz.attempt.AttemptStatus;
import org.sitare.quiz.attempt.AttemptView;
import org.sitare.quiz.bank.QuestionRowParser;
import org.sitare.quiz.bank.QuestionService;
import org.sitare.quiz.common.ApiException;
import org.sitare.quiz.identity.FacultyRepository;
import org.sitare.quiz.identity.StudentAuthService;
import org.sitare.quiz.identity.StudentPrincipal;
import org.sitare.quiz.quiz.QuizService;
import org.sitare.quiz.quiz.QuizSettings;
import org.sitare.quiz.results.ResultsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** End-to-end flow against a real PostgreSQL: setup, login, answer, submit, grade, export. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class QuizFlowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired FacultyRepository faculty;
    @Autowired QuestionService questions;
    @Autowired QuizService quizzes;
    @Autowired StudentAuthService studentAuth;
    @Autowired AttemptService attempts;
    @Autowired ResultsService results;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;

    private static final String BANK = """
            topic,type,question,marks,correct,option1,option2,option3
            OOP,SINGLE,Q1 single,1,2,a,b,c
            OOP,MULTI,Q2 multi,2,1;3,a,b,c
            OOP,TRUE_FALSE,Q3 true false,1,false,,,
            """;

    @Test
    void fullQuizFlow() throws Exception {
        String email = "f" + System.nanoTime() + "@sitare.org";
        faculty.insert(email, "Faculty", "{noop}x");
        long facultyId = faculty.findByEmail(email).orElseThrow().getId();

        QuestionRowParser.ParseResult imported = questions.importFile(facultyId, "bank.csv", stream(BANK));
        assertThat(imported.errors()).isEmpty();
        List<Long> qids = questions.list(facultyId).stream().map(q -> q.id()).toList();
        assertThat(qids).hasSize(3);

        long quizId = quizzes.create(facultyId, new QuizSettings("Flow quiz", "OOP", Instant.now().minusSeconds(60),
                30, 10, true, new BigDecimal("0.50"), false, 30));
        quizzes.setQuestions(facultyId, quizId, qids);
        String roll = "R" + System.nanoTime();
        QuizService.RosterResult roster = quizzes.uploadRoster(facultyId, quizId, "roster.csv",
                stream("roll_no,name,email\n" + roll + ",Test Student,\n"));
        assertThat(roster.errors()).isEmpty();
        String otp = roster.issued().get(0).otp();
        quizzes.publish(facultyId, quizId);
        String code = quizzes.owned(facultyId, quizId).code();

        // Wrong password is rejected; right one logs in.
        assertThat(studentAuth.login(code, roll, "WRONGPWD")).isInstanceOf(StudentAuthService.Failure.class);
        StudentPrincipal me = ((StudentAuthService.Success) studentAuth.login(code, roll, otp)).principal();

        AttemptView view = attempts.start(me);
        assertThat(view.status()).isEqualTo(AttemptStatus.IN_PROGRESS);
        assertThat(view.questions()).hasSize(3);
        assertThat(view.remainingSeconds()).isBetween(29 * 60L, 30 * 60L);
        assertThat(view.correctOptionIds()).isEmpty();

        // Answer everything correctly except the multi question (wrong -> -1 with negative marking).
        for (AttemptView.QuestionView q : view.questions()) {
            List<Long> correct = jdbc.sql("SELECT id FROM question_option WHERE question_id = :q AND is_correct ORDER BY id")
                    .param("q", q.id()).query(Long.class).list();
            List<Long> chosen = q.type().equals("MULTI") ? List.of(correct.get(0)) : correct;
            assertThat(attempts.saveAnswer(me, q.id(), chosen, false, 100).applied()).isTrue();
        }

        // An older, retried request must not overwrite the newer answer.
        AttemptView.QuestionView first = view.questions().get(0);
        assertThat(attempts.saveAnswer(me, first.id(), List.of(), false, 50).applied()).isFalse();

        // Single-choice questions reject two options.
        AttemptView.QuestionView single = view.questions().stream().filter(q -> q.type().equals("SINGLE")).findFirst().orElseThrow();
        assertThatThrownBy(() -> attempts.saveAnswer(me, single.id(),
                single.options().stream().map(AttemptView.OptionView::id).limit(2).toList(), false, 200))
                .isInstanceOf(ApiException.class);

        // A second login replaces the first session.
        StudentPrincipal second = ((StudentAuthService.Success) studentAuth.login(code, roll, otp)).principal();
        assertThatThrownBy(() -> attempts.view(me)).isInstanceOf(ApiException.class)
                .hasMessageContaining("another device");

        AttemptView done = attempts.submit(second);
        assertThat(done.status()).isEqualTo(AttemptStatus.SUBMITTED);
        // 1 (single) + 1 (true/false) - 0.5 x 2 (wrong multi) = 1
        assertThat(done.score()).isEqualByComparingTo("1");
        assertThat(done.maxScore()).isEqualByComparingTo("4");

        // Submitting twice is harmless; saves after submit are refused.
        assertThat(attempts.submit(second).score()).isEqualByComparingTo("1");
        assertThatThrownBy(() -> attempts.saveAnswer(second, first.id(), List.of(), false, 999))
                .isInstanceOf(ApiException.class);

        // Answers are hidden until faculty share them.
        assertThat(attempts.view(second).correctOptionIds()).isEmpty();
        quizzes.shareAnswers(facultyId, quizId, true);
        assertThat(attempts.view(second).correctOptionIds()).hasSize(3);

        var rows = results.results(quizId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).score()).isEqualByComparingTo("1");
        assertThat(rows.get(0).secondLogins()).isEqualTo(1);
        assertThat(results.excel("Flow quiz", quizId)).isNotEmpty();
    }

    @Test
    void studentApiNeverExposesTheAnswerKeyAndNeedsLogin() throws Exception {
        mvc.perform(get("/api/attempt")).andExpect(status().isUnauthorized());
        mvc.perform(get("/faculty/quizzes")).andExpect(status().is3xxRedirection());

        var student = new UsernamePasswordAuthenticationToken(
                new StudentPrincipal(999_999, "X", "X", 999_999, "t"), null,
                List.of(new SimpleGrantedAuthority("ROLE_STUDENT")));
        mvc.perform(get("/faculty/quizzes").with(authentication(student))).andExpect(status().isForbidden());
        mvc.perform(post("/api/attempt/submit").with(authentication(student))).andExpect(status().isForbidden());
        mvc.perform(post("/api/attempt/submit").with(authentication(student)).with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    void everyPageRenders() throws Exception {
        String email = "p" + System.nanoTime() + "@sitare.org";
        faculty.insert(email, "Page Tester", "{noop}x");
        var me = faculty.findByEmail(email).orElseThrow();
        var facultyAuth = new UsernamePasswordAuthenticationToken(me, null, me.getAuthorities());

        questions.importFile(me.getId(), "bank.csv", stream(BANK));
        long quizId = quizzes.create(me.getId(), new QuizSettings("Page quiz", "OOP", Instant.now().minusSeconds(60),
                30, 10, false, new BigDecimal("0.50"), false, 30));
        quizzes.setQuestions(me.getId(), quizId, questions.list(me.getId()).stream().map(q -> q.id()).toList());

        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/login")).andExpect(status().isOk());
        mvc.perform(get("/student/login")).andExpect(status().isOk());
        mvc.perform(get("/faculty/quizzes").with(authentication(facultyAuth))).andExpect(status().isOk());
        mvc.perform(get("/faculty/questions").with(authentication(facultyAuth))).andExpect(status().isOk());
        mvc.perform(get("/faculty/quizzes/" + quizId).with(authentication(facultyAuth))).andExpect(status().isOk());

        var roster = new org.springframework.mock.web.MockMultipartFile("file", "roster.csv", "text/csv",
                ("roll_no,name,email\nP" + System.nanoTime() + ",Page Student,\n").getBytes(StandardCharsets.UTF_8));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/faculty/quizzes/" + quizId + "/roster").file(roster)
                        .with(authentication(facultyAuth)).with(csrf()))
                .andExpect(status().isOk());
        quizzes.publish(me.getId(), quizId);
        mvc.perform(get("/faculty/quizzes/" + quizId).with(authentication(facultyAuth))).andExpect(status().isOk());
        mvc.perform(get("/faculty/quizzes/" + quizId + "/results.xlsx").with(authentication(facultyAuth)))
                .andExpect(status().isOk());

        var student = new UsernamePasswordAuthenticationToken(
                new StudentPrincipal(1, "X", "X", quizId, "t"), null, List.of(new SimpleGrantedAuthority("ROLE_STUDENT")));
        mvc.perform(get("/student/quiz").with(authentication(student))).andExpect(status().isOk());
    }

    private static ByteArrayInputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
