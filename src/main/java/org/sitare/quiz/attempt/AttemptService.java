package org.sitare.quiz.attempt;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.sitare.quiz.bank.Question;
import org.sitare.quiz.bank.QuestionRepository;
import org.sitare.quiz.common.ApiException;
import org.sitare.quiz.common.AppProperties;
import org.sitare.quiz.grading.ScoreCalculator;
import org.sitare.quiz.identity.StudentPrincipal;
import org.sitare.quiz.quiz.AttemptAdjuster;
import org.sitare.quiz.quiz.Quiz;
import org.sitare.quiz.quiz.QuizRepository;
import org.sitare.quiz.quiz.QuizStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The attempt lifecycle: start, save, submit and auto-submit (design F1-F3, A1-A6). */
@Service
public class AttemptService implements AttemptAdjuster {

    private final AttemptRepository attempts;
    private final QuizRepository quizzes;
    private final QuestionRepository questions;
    private final Clock clock;
    private final Duration grace;

    public AttemptService(AttemptRepository attempts, QuizRepository quizzes, QuestionRepository questions,
                          Clock clock, AppProperties props) {
        this.attempts = attempts;
        this.quizzes = quizzes;
        this.questions = questions;
        this.clock = clock;
        this.grace = Duration.ofSeconds(props.quiz() == null ? 5 : props.quiz().graceSeconds());
    }

    // ------------------------------------------------------------------ login

    /** Called on student login: the new session replaces any older one on an existing attempt. */
    @Transactional
    public void bindSession(long quizId, long studentId, String token) {
        attempts.find(quizId, studentId).ifPresent(a -> {
            if (!a.status().finished()) {
                attempts.replaceSession(a.id(), token, true);
            } else {
                attempts.replaceSession(a.id(), token, false);
            }
        });
    }

    // ------------------------------------------------------------------ start / view

    @Transactional
    public AttemptView start(StudentPrincipal me) {
        Quiz quiz = quiz(me.quizId());
        var existing = attempts.find(quiz.id(), me.studentId());
        if (existing.isPresent()) {
            return view(me);
        }
        Instant now = clock.instant();
        if (quiz.status() != QuizStatus.PUBLISHED) {
            throw ApiException.conflict("NOT_OPEN", "This quiz is not open.");
        }
        if (now.isBefore(quiz.startsAt())) {
            throw ApiException.conflict("NOT_STARTED", "The quiz has not started yet. Please wait for the start time.");
        }
        if (now.isAfter(quiz.lateJoinUntil())) {
            throw ApiException.conflict("TOO_LATE", "The time to start this quiz has passed. Please speak to your teacher.");
        }

        List<Long> questionIds = quizzes.questionIds(quiz.id());
        List<Question> qs = questions.findByIds(questionIds);
        if (qs.isEmpty()) {
            throw ApiException.conflict("NO_QUESTIONS", "This quiz has no questions.");
        }
        Map<Long, List<Long>> optionIds = new LinkedHashMap<>();
        BigDecimal max = BigDecimal.ZERO;
        for (Question q : qs) {
            optionIds.put(q.id(), q.options().stream().map(Question.Option::id).toList());
            max = max.add(q.marks());
        }

        int extra = quizzes.extraMinutes(quiz.id(), me.studentId());
        Instant deadline = now.plus(Duration.ofMinutes((long) quiz.durationMin() + quiz.extensionMin() + extra));

        var created = attempts.insertIfAbsent(quiz.id(), me.studentId(), now, deadline, max, me.sessionToken());
        if (created.isPresent()) {
            long attemptId = created.get();
            int position = 1;
            for (var e : QuestionOrderRandomizer.shuffle(quiz.id(), me.studentId(), optionIds).entrySet()) {
                attempts.insertQuestion(attemptId, e.getKey(), position++, e.getValue());
            }
        }
        return view(me);
    }

    @Transactional
    public AttemptView view(StudentPrincipal me) {
        Quiz quiz = quiz(me.quizId());
        AttemptRepository.Attempt attempt = ownAttempt(me);
        Instant now = clock.instant();
        if (attempt.status() == AttemptStatus.IN_PROGRESS && now.isAfter(attempt.deadlineAt().plus(grace))) {
            finish(attempt.id(), AttemptStatus.AUTO_SUBMITTED);
            attempt = attempts.find(me.quizId(), me.studentId()).orElseThrow();
        }

        List<AttemptRepository.AttemptQuestion> order = attempts.questions(attempt.id());
        Map<Long, Question> byId = questions.findByIds(order.stream().map(AttemptRepository.AttemptQuestion::questionId).toList())
                .stream().collect(Collectors.toMap(Question::id, Function.identity()));

        List<AttemptView.QuestionView> qviews = new ArrayList<>();
        Map<Long, List<Long>> correct = new LinkedHashMap<>();
        boolean revealAnswers = attempt.status().finished() && quiz.answersShared();
        int number = 1;
        for (AttemptRepository.AttemptQuestion aq : order) {
            Question q = byId.get(aq.questionId());
            if (q == null) {
                continue;
            }
            Map<Long, Question.Option> opts = q.options().stream()
                    .collect(Collectors.toMap(Question.Option::id, Function.identity()));
            List<AttemptView.OptionView> ov = new ArrayList<>();
            for (Long oid : aq.optionOrder()) {
                Question.Option o = opts.get(oid);
                if (o != null) {
                    ov.add(new AttemptView.OptionView(o.id(), o.text()));
                }
            }
            qviews.add(new AttemptView.QuestionView(q.id(), number++, q.type().name(), q.stem(), q.marks(), ov));
            if (revealAnswers) {
                correct.put(q.id(), List.copyOf(q.correctOptionIds()));
            }
        }

        Map<Long, AttemptView.AnswerView> answers = new LinkedHashMap<>();
        attempts.answers(attempt.id()).forEach((qid, a) ->
                answers.put(qid, new AttemptView.AnswerView(List.copyOf(a.selected()), a.markedForReview(), a.clientSeq())));

        long remaining = attempt.status().finished() ? 0
                : Math.max(0, Duration.between(now, attempt.deadlineAt()).getSeconds());

        return new AttemptView(quiz.title(), me.name(), me.rollNo(), attempt.status(), remaining, qviews, answers,
                attempt.status().finished() ? attempt.score() : null,
                attempt.maxScore(), revealAnswers, correct);
    }

    // ------------------------------------------------------------------ save

    public record SaveResult(boolean applied, long remainingSeconds) {
    }

    @Transactional
    public SaveResult saveAnswer(StudentPrincipal me, long questionId, List<Long> selectedIds, boolean marked,
                                 long clientSeq) {
        AttemptRepository.Attempt attempt = attempts.findForSave(me.quizId(), me.studentId())
                .orElseThrow(() -> ApiException.conflict("NO_ATTEMPT", "Start the quiz first."));
        checkSession(attempt, me);
        Instant now = clock.instant();
        if (attempt.status().finished()) {
            throw ApiException.conflict("CLOSED", "This attempt has already been submitted.");
        }
        if (now.isAfter(attempt.deadlineAt().plus(grace))) {
            throw ApiException.conflict("TIME_UP", "Time is up. Your saved answers have been submitted.");
        }

        AttemptRepository.AttemptQuestion aq = attempts.questions(attempt.id()).stream()
                .filter(q -> q.questionId() == questionId).findFirst()
                .orElseThrow(() -> ApiException.badRequest("UNKNOWN_QUESTION", "That question is not part of this quiz."));
        Set<Long> selected = new LinkedHashSet<>(selectedIds == null ? List.of() : selectedIds);
        if (!new LinkedHashSet<>(aq.optionOrder()).containsAll(selected)) {
            throw ApiException.badRequest("UNKNOWN_OPTION", "An option does not belong to this question.");
        }
        Question q = questions.findById(questionId).orElseThrow();
        if (q.type().singleChoice() && selected.size() > 1) {
            throw ApiException.badRequest("ONE_ONLY", "Choose only one option for this question.");
        }

        boolean applied = attempts.upsertAnswer(attempt.id(), questionId, selected, marked, clientSeq, now);
        attempts.touch(attempt.id(), now);
        return new SaveResult(applied, Math.max(0, Duration.between(now, attempt.deadlineAt()).getSeconds()));
    }

    // ------------------------------------------------------------------ heartbeat / submit

    public record Heartbeat(AttemptStatus status, long remainingSeconds) {
    }

    @Transactional
    public Heartbeat heartbeat(StudentPrincipal me) {
        AttemptRepository.Attempt attempt = ownAttempt(me);
        Instant now = clock.instant();
        if (attempt.status() == AttemptStatus.IN_PROGRESS && now.isAfter(attempt.deadlineAt().plus(grace))) {
            finish(attempt.id(), AttemptStatus.AUTO_SUBMITTED);
            return new Heartbeat(AttemptStatus.AUTO_SUBMITTED, 0);
        }
        attempts.touch(attempt.id(), now);
        long remaining = attempt.status().finished() ? 0
                : Math.max(0, Duration.between(now, attempt.deadlineAt()).getSeconds());
        return new Heartbeat(attempt.status(), remaining);
    }

    @Transactional
    public AttemptView submit(StudentPrincipal me) {
        AttemptRepository.Attempt attempt = ownAttempt(me);
        finish(attempt.id(), AttemptStatus.SUBMITTED);
        return view(me);
    }

    /**
     * Grades and closes an attempt exactly once. A second call, or a race between a manual submit and the
     * auto-submit job, is a no-op because the row lock is taken and the status re-checked first.
     */
    @Transactional
    public void finish(long attemptId, AttemptStatus status) {
        AttemptRepository.Attempt attempt = attempts.findForUpdate(attemptId).orElseThrow();
        if (attempt.status().finished()) {
            return;
        }
        Quiz quiz = quiz(attempt.quizId());
        List<Long> qids = attempts.questions(attemptId).stream().map(AttemptRepository.AttemptQuestion::questionId).toList();
        List<ScoreCalculator.GradableQuestion> gradable = questions.findByIds(qids).stream()
                .map(q -> new ScoreCalculator.GradableQuestion(q.id(), q.marks(), q.correctOptionIds()))
                .toList();
        Map<Long, Set<Long>> selected = new HashMap<>();
        attempts.answers(attemptId).forEach((qid, a) -> selected.put(qid, a.selected()));

        ScoreCalculator.Result result = ScoreCalculator.grade(gradable, selected, quiz.negativeMarking(),
                quiz.negativeFraction());
        attempts.finish(attemptId, status, result.score(), result.maxScore(), clock.instant());
    }

    // ------------------------------------------------------------------ AttemptAdjuster

    @Override
    public void extendAll(long quizId, int minutes) {
        attempts.extendAll(quizId, minutes);
    }

    @Override
    public void extendOne(long quizId, long studentId, int minutes) {
        attempts.extendOne(quizId, studentId, minutes);
    }

    @Override
    public void submitAllOpen(long quizId) {
        for (Long id : attempts.openIds(quizId)) {
            finish(id, AttemptStatus.AUTO_SUBMITTED);
        }
    }

    // ------------------------------------------------------------------ helpers

    private Quiz quiz(long quizId) {
        return quizzes.findById(quizId).orElseThrow(() -> ApiException.notFound("Quiz not found."));
    }

    private AttemptRepository.Attempt ownAttempt(StudentPrincipal me) {
        AttemptRepository.Attempt attempt = attempts.find(me.quizId(), me.studentId())
                .orElseThrow(() -> ApiException.conflict("NO_ATTEMPT", "Start the quiz first."));
        checkSession(attempt, me);
        return attempt;
    }

    private static void checkSession(AttemptRepository.Attempt attempt, StudentPrincipal me) {
        if (!attempt.sessionToken().equals(me.sessionToken())) {
            throw ApiException.conflict("SESSION_REPLACED",
                    "You have logged in on another device or tab. Continue the quiz there.");
        }
    }
}
