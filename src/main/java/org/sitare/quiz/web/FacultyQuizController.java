package org.sitare.quiz.web;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.function.Supplier;

import org.sitare.quiz.bank.QuestionService;
import org.sitare.quiz.common.ApiException;
import org.sitare.quiz.identity.FacultyPrincipal;
import org.sitare.quiz.quiz.Quiz;
import org.sitare.quiz.quiz.QuizService;
import org.sitare.quiz.quiz.QuizSettings;
import org.sitare.quiz.results.ResultsService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/faculty/quizzes")
public class FacultyQuizController {

    private final QuizService quizzes;
    private final QuestionService questions;
    private final ResultsService results;
    private final Fmt fmt;

    public FacultyQuizController(QuizService quizzes, QuestionService questions, ResultsService results, Fmt fmt) {
        this.quizzes = quizzes;
        this.questions = questions;
        this.results = results;
        this.fmt = fmt;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal FacultyPrincipal me, Model model) {
        model.addAttribute("me", me);
        model.addAttribute("quizzes", quizzes.list(me.getId()));
        return "faculty/quizzes";
    }

    @PostMapping
    public String create(@AuthenticationPrincipal FacultyPrincipal me,
                         @RequestParam("title") String title,
                         @RequestParam(name = "course", required = false) String course,
                         @RequestParam("startsAt") String startsAt,
                         @RequestParam(name = "durationMin", defaultValue = "30") int durationMin,
                         @RequestParam(name = "lateJoinMin", defaultValue = "10") int lateJoinMin,
                         RedirectAttributes flash) {
        try {
            QuizSettings settings = new QuizSettings(title.trim(), blankToNull(course), fmt.parseInput(startsAt),
                    durationMin, lateJoinMin, false, new BigDecimal("0.50"), false, 30);
            long id = quizzes.create(me.getId(), settings);
            flash.addFlashAttribute("message", "Quiz created. Add questions and the roster, then publish.");
            return "redirect:/faculty/quizzes/" + id;
        } catch (ApiException | java.time.format.DateTimeParseException e) {
            flash.addFlashAttribute("error", e.getMessage());
            return "redirect:/faculty/quizzes";
        }
    }

    @GetMapping("/{id}")
    public String detail(@AuthenticationPrincipal FacultyPrincipal me, @PathVariable long id, Model model) {
        Quiz quiz = quizzes.owned(me.getId(), id);
        model.addAttribute("me", me);
        model.addAttribute("quiz", quiz);
        model.addAttribute("bank", questions.list(me.getId()));
        model.addAttribute("selected", new HashSet<>(quizzes.questionIds(id)));
        model.addAttribute("selectedCount", quizzes.questionIds(id).size());
        model.addAttribute("roster", quizzes.roster(id));
        model.addAttribute("results", results.results(id));
        return "faculty/quiz";
    }

    @PostMapping("/{id}/settings")
    public String settings(@AuthenticationPrincipal FacultyPrincipal me, @PathVariable long id,
                           @RequestParam("title") String title,
                           @RequestParam(name = "course", required = false) String course,
                           @RequestParam("startsAt") String startsAt,
                           @RequestParam("durationMin") int durationMin,
                           @RequestParam("lateJoinMin") int lateJoinMin,
                           @RequestParam(name = "negativeMarking", defaultValue = "false") boolean negativeMarking,
                           @RequestParam(name = "negativeFraction", defaultValue = "0.5") BigDecimal negativeFraction,
                           @RequestParam(name = "webcamEnabled", defaultValue = "false") boolean webcamEnabled,
                           @RequestParam(name = "snapshotRetentionDays", defaultValue = "30") int retention,
                           RedirectAttributes flash) {
        return act(id, flash, "Settings saved.", () -> {
            quizzes.updateSettings(me.getId(), id, new QuizSettings(title.trim(), blankToNull(course),
                    fmt.parseInput(startsAt), durationMin, lateJoinMin, negativeMarking, negativeFraction,
                    webcamEnabled, retention));
            return null;
        });
    }

    @PostMapping("/{id}/questions")
    public String setQuestions(@AuthenticationPrincipal FacultyPrincipal me, @PathVariable long id,
                               @RequestParam(name = "questionIds", required = false) List<Long> questionIds,
                               RedirectAttributes flash) {
        return act(id, flash, "Questions saved.", () -> {
            quizzes.setQuestions(me.getId(), id, questionIds == null ? List.of() : questionIds);
            return null;
        });
    }

    /** Uploads the roster and shows the one-time passwords once, on a printable page. */
    @PostMapping("/{id}/roster")
    public String roster(@AuthenticationPrincipal FacultyPrincipal me, @PathVariable long id,
                         @RequestParam("file") MultipartFile file, Model model, RedirectAttributes flash)
            throws IOException {
        if (file.isEmpty()) {
            flash.addFlashAttribute("error", "Choose a roster file (CSV or Excel).");
            return "redirect:/faculty/quizzes/" + id;
        }
        QuizService.RosterResult result;
        try {
            result = quizzes.uploadRoster(me.getId(), id, file.getOriginalFilename(), file.getInputStream());
        } catch (ApiException e) {
            flash.addFlashAttribute("error", e.getMessage());
            return "redirect:/faculty/quizzes/" + id;
        }
        if (!result.errors().isEmpty()) {
            flash.addFlashAttribute("error", "The roster was not saved. Fix these rows and upload again.");
            flash.addFlashAttribute("rosterErrors", result.errors());
            return "redirect:/faculty/quizzes/" + id;
        }
        model.addAttribute("quiz", quizzes.owned(me.getId(), id));
        model.addAttribute("issued", result.issued());
        return "faculty/otps";
    }

    @PostMapping("/{id}/publish")
    public String publish(@AuthenticationPrincipal FacultyPrincipal me, @PathVariable long id, RedirectAttributes flash) {
        return act(id, flash, "Quiz published. Students can log in with the quiz code and their passwords.", () -> {
            quizzes.publish(me.getId(), id);
            return null;
        });
    }

    @PostMapping("/{id}/close")
    public String close(@AuthenticationPrincipal FacultyPrincipal me, @PathVariable long id, RedirectAttributes flash) {
        return act(id, flash, "Quiz closed. All open attempts were submitted and graded.", () -> {
            quizzes.close(me.getId(), id);
            return null;
        });
    }

    @PostMapping("/{id}/extend")
    public String extend(@AuthenticationPrincipal FacultyPrincipal me, @PathVariable long id,
                         @RequestParam("minutes") int minutes,
                         @RequestParam(name = "rollNo", required = false) String rollNo,
                         RedirectAttributes flash) {
        String who = rollNo == null || rollNo.isBlank() ? "the whole class" : rollNo.trim();
        return act(id, flash, "Added " + minutes + " minutes for " + who + ".", () -> {
            quizzes.extendTime(me.getId(), id, minutes, rollNo);
            return null;
        });
    }

    @PostMapping("/{id}/share-answers")
    public String shareAnswers(@AuthenticationPrincipal FacultyPrincipal me, @PathVariable long id,
                               @RequestParam("shared") boolean shared, RedirectAttributes flash) {
        return act(id, flash, shared ? "Correct answers are now visible to students who have submitted."
                : "Correct answers are hidden again.", () -> {
            quizzes.shareAnswers(me.getId(), id, shared);
            return null;
        });
    }

    @GetMapping("/{id}/results.xlsx")
    public ResponseEntity<byte[]> excel(@AuthenticationPrincipal FacultyPrincipal me, @PathVariable long id)
            throws IOException {
        Quiz quiz = quizzes.owned(me.getId(), id);
        byte[] body = results.excel(quiz.title(), id);
        String filename = (quiz.title().replaceAll("[^A-Za-z0-9 _-]", "").trim().replace(' ', '_')) + "_marks.xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    private String act(long id, RedirectAttributes flash, String success, Supplier<Void> action) {
        try {
            action.get();
            flash.addFlashAttribute("message", success);
        } catch (ApiException | java.time.format.DateTimeParseException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/faculty/quizzes/" + id;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
