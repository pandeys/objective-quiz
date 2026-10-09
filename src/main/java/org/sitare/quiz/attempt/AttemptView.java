package org.sitare.quiz.attempt;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * What a student's browser receives. It never contains which options are correct, except in
 * {@link #correctOptionIds} after the attempt is finished and faculty have shared the answers.
 */
public record AttemptView(
        String quizTitle,
        String studentName,
        String rollNo,
        AttemptStatus status,
        long remainingSeconds,
        List<QuestionView> questions,
        Map<Long, AnswerView> answers,
        BigDecimal score,
        BigDecimal maxScore,
        boolean answersShared,
        Map<Long, List<Long>> correctOptionIds) {

    public record QuestionView(long id, int number, String type, String stem, BigDecimal marks,
                               List<OptionView> options) {
    }

    public record OptionView(long id, String text) {
    }

    public record AnswerView(List<Long> selected, boolean markedForReview, long clientSeq) {
    }
}
