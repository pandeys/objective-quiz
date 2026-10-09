package org.sitare.quiz.grading;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Set;

/**
 * Pure scoring rules (design A5).
 *
 * <ul>
 *   <li>Exact match of the selected set with the correct set earns the question's full marks.</li>
 *   <li>No partial credit for multi-correct questions.</li>
 *   <li>A wrong, non-empty selection earns {@code -fraction x marks} only when negative marking is on.</li>
 *   <li>An unanswered or cleared question earns 0.</li>
 *   <li>The total never goes below 0.</li>
 * </ul>
 */
public final class ScoreCalculator {

    private ScoreCalculator() {
    }

    /** One question as the grader sees it. */
    public record GradableQuestion(long questionId, BigDecimal marks, Set<Long> correctOptionIds) {
    }

    public record Result(BigDecimal score, BigDecimal maxScore, int correct, int wrong, int unanswered) {
    }

    public static Result grade(Iterable<GradableQuestion> questions,
                               Map<Long, Set<Long>> selectedByQuestion,
                               boolean negativeMarking,
                               BigDecimal negativeFraction) {
        BigDecimal score = BigDecimal.ZERO;
        BigDecimal max = BigDecimal.ZERO;
        int correct = 0;
        int wrong = 0;
        int unanswered = 0;

        for (GradableQuestion q : questions) {
            max = max.add(q.marks());
            Set<Long> selected = selectedByQuestion.getOrDefault(q.questionId(), Set.of());
            if (selected.isEmpty()) {
                unanswered++;
            } else if (selected.equals(q.correctOptionIds())) {
                correct++;
                score = score.add(q.marks());
            } else {
                wrong++;
                if (negativeMarking) {
                    score = score.subtract(q.marks().multiply(negativeFraction));
                }
            }
        }
        if (score.signum() < 0) {
            score = BigDecimal.ZERO;
        }
        return new Result(score.setScale(2, RoundingMode.HALF_UP), max.setScale(2, RoundingMode.HALF_UP),
                correct, wrong, unanswered);
    }
}
