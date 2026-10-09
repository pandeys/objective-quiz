package org.sitare.quiz.quiz;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.sitare.quiz.bank.Question;
import org.sitare.quiz.bank.QuestionRepository;
import org.sitare.quiz.bank.SpreadsheetReader;
import org.sitare.quiz.common.ApiException;
import org.sitare.quiz.identity.OtpGenerator;
import org.sitare.quiz.identity.StudentRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuizService {

    /** A roster row with the plain one-time password, shown to faculty once and never stored. */
    public record IssuedOtp(String rollNo, String name, String otp) {
    }

    public record RosterResult(List<IssuedOtp> issued, List<String> errors) {
    }

    private final QuizRepository quizzes;
    private final QuestionRepository questions;
    private final StudentRepository students;
    private final PasswordEncoder encoder;
    private final AttemptAdjuster attemptAdjuster;

    public QuizService(QuizRepository quizzes, QuestionRepository questions, StudentRepository students,
                       PasswordEncoder encoder, AttemptAdjuster attemptAdjuster) {
        this.quizzes = quizzes;
        this.questions = questions;
        this.students = students;
        this.encoder = encoder;
        this.attemptAdjuster = attemptAdjuster;
    }

    public List<Quiz> list(long facultyId) {
        return quizzes.listByFaculty(facultyId);
    }

    /** Loads a quiz and checks that it belongs to this faculty member. */
    public Quiz owned(long facultyId, long quizId) {
        Quiz quiz = quizzes.findById(quizId).orElseThrow(() -> ApiException.notFound("Quiz not found."));
        if (quiz.facultyId() != facultyId) {
            throw ApiException.forbidden("This quiz belongs to another faculty member.");
        }
        return quiz;
    }

    @Transactional
    public long create(long facultyId, QuizSettings settings) {
        validate(settings);
        String code;
        do {
            code = OtpGenerator.newQuizCode();
        } while (quizzes.codeExists(code));
        return quizzes.insert(facultyId, code, settings);
    }

    @Transactional
    public void updateSettings(long facultyId, long quizId, QuizSettings settings) {
        Quiz quiz = owned(facultyId, quizId);
        if (quiz.status() != QuizStatus.DRAFT) {
            throw ApiException.conflict("NOT_DRAFT", "Settings can only be changed before the quiz is published.");
        }
        validate(settings);
        quizzes.updateSettings(quizId, settings);
    }

    public List<Long> questionIds(long quizId) {
        return quizzes.questionIds(quizId);
    }

    @Transactional
    public void setQuestions(long facultyId, long quizId, List<Long> questionIds) {
        Quiz quiz = owned(facultyId, quizId);
        if (quiz.status() != QuizStatus.DRAFT) {
            throw ApiException.conflict("NOT_DRAFT", "Questions can only be changed before the quiz is published.");
        }
        Set<Long> unique = new LinkedHashSet<>(questionIds);
        List<Question> found = questions.findByIds(unique);
        for (Question q : found) {
            if (q.facultyId() != facultyId) {
                throw ApiException.forbidden("A selected question belongs to another faculty member.");
            }
        }
        quizzes.replaceQuestions(quizId, found.stream().map(Question::id).toList());
    }

    /**
     * Reads a roster file (columns: roll_no, name, email) and issues a fresh one-time password to every student
     * in it. Students already on the roster get a new password, which replaces the old one.
     */
    @Transactional
    public RosterResult uploadRoster(long facultyId, long quizId, String filename, InputStream in) throws IOException {
        Quiz quiz = owned(facultyId, quizId);
        if (quiz.status() == QuizStatus.CLOSED) {
            throw ApiException.conflict("CLOSED", "The quiz is closed.");
        }
        List<List<String>> rows = SpreadsheetReader.read(filename, in);
        List<String> errors = new ArrayList<>();
        if (rows.isEmpty()) {
            return new RosterResult(List.of(), List.of("The file is empty."));
        }
        List<String> header = rows.get(0).stream().map(h -> h.trim().toLowerCase(Locale.ROOT).replace(' ', '_')).toList();
        int rollIdx = indexOf(header, "roll_no", "roll", "roll_number");
        int nameIdx = indexOf(header, "name", "student_name");
        int emailIdx = indexOf(header, "email", "email_id");
        if (rollIdx < 0 || nameIdx < 0) {
            return new RosterResult(List.of(), List.of("Header row needs the columns roll_no and name (email is optional)."));
        }

        record Row(String roll, String name, String email) {
        }
        List<Row> valid = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            String roll = get(r, rollIdx);
            String name = get(r, nameIdx);
            if (roll.isEmpty() && name.isEmpty()) {
                continue;
            }
            if (roll.isEmpty() || name.isEmpty()) {
                errors.add("Row " + (i + 1) + ": roll_no and name are both required.");
                continue;
            }
            if (!seen.add(roll.toUpperCase(Locale.ROOT))) {
                errors.add("Row " + (i + 1) + ": roll number " + roll + " appears twice.");
                continue;
            }
            valid.add(new Row(roll, name, get(r, emailIdx)));
        }
        if (!errors.isEmpty()) {
            return new RosterResult(List.of(), errors);
        }

        List<IssuedOtp> issued = new ArrayList<>();
        for (Row row : valid) {
            long studentId = students.upsert(row.roll(), row.name(), row.email());
            String otp = OtpGenerator.newOtp();
            quizzes.upsertRoster(quizId, studentId, encoder.encode(otp));
            issued.add(new IssuedOtp(row.roll(), row.name(), otp));
        }
        return new RosterResult(issued, List.of());
    }

    public List<QuizRepository.RosterEntry> roster(long quizId) {
        return quizzes.roster(quizId);
    }

    @Transactional
    public void publish(long facultyId, long quizId) {
        Quiz quiz = owned(facultyId, quizId);
        if (quiz.status() != QuizStatus.DRAFT) {
            throw ApiException.conflict("NOT_DRAFT", "The quiz is already published.");
        }
        if (quizzes.questionIds(quizId).isEmpty()) {
            throw ApiException.badRequest("NO_QUESTIONS", "Add at least one question before publishing.");
        }
        if (quizzes.roster(quizId).isEmpty()) {
            throw ApiException.badRequest("NO_ROSTER", "Upload the class roster before publishing.");
        }
        quizzes.setStatus(quizId, QuizStatus.PUBLISHED);
    }

    @Transactional
    public void close(long facultyId, long quizId) {
        owned(facultyId, quizId);
        attemptAdjuster.submitAllOpen(quizId);
        quizzes.setStatus(quizId, QuizStatus.CLOSED);
    }

    /**
     * Gives extra time to the whole class, or to one student when a roll number is given.
     * Running attempts get the extra minutes immediately.
     */
    @Transactional
    public void extendTime(long facultyId, long quizId, int minutes, String rollNo) {
        Quiz quiz = owned(facultyId, quizId);
        if (minutes < 1 || minutes > 60) {
            throw ApiException.badRequest("INVALID_MINUTES", "Extension must be between 1 and 60 minutes.");
        }
        if (quiz.status() != QuizStatus.PUBLISHED) {
            throw ApiException.conflict("NOT_RUNNING", "Time can only be extended for a published quiz.");
        }
        if (rollNo == null || rollNo.isBlank()) {
            quizzes.addExtension(quizId, minutes);
            attemptAdjuster.extendAll(quizId, minutes);
        } else {
            var student = students.findByRoll(rollNo)
                    .orElseThrow(() -> ApiException.notFound("No student with roll number " + rollNo + "."));
            if (quizzes.otpHash(quizId, student.id()).isEmpty()) {
                throw ApiException.notFound("Roll number " + rollNo + " is not on this quiz's roster.");
            }
            quizzes.addStudentExtra(quizId, student.id(), minutes);
            attemptAdjuster.extendOne(quizId, student.id(), minutes);
        }
    }

    @Transactional
    public void shareAnswers(long facultyId, long quizId, boolean shared) {
        owned(facultyId, quizId);
        quizzes.setAnswersShared(quizId, shared);
    }

    private static void validate(QuizSettings s) {
        if (s.title() == null || s.title().isBlank()) {
            throw ApiException.badRequest("INVALID", "Title is required.");
        }
        if (s.startsAt() == null) {
            throw ApiException.badRequest("INVALID", "Start date and time are required.");
        }
        if (s.durationMin() < 1 || s.durationMin() > 300) {
            throw ApiException.badRequest("INVALID", "Duration must be between 1 and 300 minutes.");
        }
        if (s.lateJoinMin() < 0 || s.lateJoinMin() > s.durationMin()) {
            throw ApiException.badRequest("INVALID", "Late-join window must be between 0 and the quiz duration.");
        }
        if (s.negativeFraction() == null || s.negativeFraction().signum() < 0
                || s.negativeFraction().compareTo(java.math.BigDecimal.ONE) > 0) {
            throw ApiException.badRequest("INVALID", "Negative marking fraction must be between 0 and 1.");
        }
        if (s.snapshotRetentionDays() < 1 || s.snapshotRetentionDays() > 365) {
            throw ApiException.badRequest("INVALID", "Snapshot retention must be between 1 and 365 days.");
        }
    }

    private static int indexOf(List<String> header, String... names) {
        for (String n : names) {
            int i = header.indexOf(n);
            if (i >= 0) {
                return i;
            }
        }
        return -1;
    }

    private static String get(List<String> row, int idx) {
        if (idx < 0 || idx >= row.size() || row.get(idx) == null) {
            return "";
        }
        return row.get(idx).trim();
    }
}
