package org.sitare.quiz.identity;

import java.util.Optional;
import java.util.UUID;

import org.sitare.quiz.attempt.AttemptService;
import org.sitare.quiz.quiz.Quiz;
import org.sitare.quiz.quiz.QuizRepository;
import org.sitare.quiz.quiz.QuizStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Student login with quiz code, roll number and one-time password (design F1). */
@Service
public class StudentAuthService {

    public sealed interface LoginResult permits Success, Failure {
    }

    public record Success(StudentPrincipal principal) implements LoginResult {
    }

    public record Failure(String message) implements LoginResult {
    }

    private static final String GENERIC_FAILURE = "Quiz code, roll number or password is not correct.";

    private final QuizRepository quizzes;
    private final StudentRepository students;
    private final PasswordEncoder encoder;
    private final LoginRateLimiter limiter;
    private final AttemptService attempts;

    public StudentAuthService(QuizRepository quizzes, StudentRepository students, PasswordEncoder encoder,
                              LoginRateLimiter limiter, AttemptService attempts) {
        this.quizzes = quizzes;
        this.students = students;
        this.encoder = encoder;
        this.limiter = limiter;
        this.attempts = attempts;
    }

    public LoginResult login(String quizCode, String rollNo, String otp) {
        if (isBlank(quizCode) || isBlank(rollNo) || isBlank(otp)) {
            return new Failure("Please fill in all three fields.");
        }
        if (limiter.blocked(rollNo)) {
            return new Failure("Too many wrong attempts. Please wait 10 minutes or ask your teacher.");
        }

        Optional<Quiz> quiz = quizzes.findByCode(quizCode);
        Optional<StudentRepository.Student> student = students.findByRoll(rollNo);
        if (quiz.isEmpty() || student.isEmpty()) {
            limiter.recordFailure(rollNo);
            return new Failure(GENERIC_FAILURE);
        }
        Optional<String> hash = quizzes.otpHash(quiz.get().id(), student.get().id());
        if (hash.isEmpty() || !encoder.matches(otp.trim().toUpperCase(), hash.get())) {
            limiter.recordFailure(rollNo);
            return new Failure(GENERIC_FAILURE);
        }
        if (quiz.get().status() == QuizStatus.DRAFT) {
            return new Failure("This quiz is not open yet.");
        }

        limiter.reset(rollNo);
        String token = UUID.randomUUID().toString();
        attempts.bindSession(quiz.get().id(), student.get().id(), token);
        return new Success(new StudentPrincipal(student.get().id(), student.get().rollNo(), student.get().name(),
                quiz.get().id(), token));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
