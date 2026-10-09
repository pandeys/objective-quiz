package org.sitare.quiz.grading;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ScoreCalculatorTest {

    private static final BigDecimal HALF = new BigDecimal("0.50");

    private final List<ScoreCalculator.GradableQuestion> questions = List.of(
            new ScoreCalculator.GradableQuestion(1, new BigDecimal("1"), Set.of(11L)),
            new ScoreCalculator.GradableQuestion(2, new BigDecimal("2"), Set.of(21L, 23L)),
            new ScoreCalculator.GradableQuestion(3, new BigDecimal("1"), Set.of(31L)));

    @Test
    void allCorrectEarnsFullMarks() {
        var r = ScoreCalculator.grade(questions, Map.of(1L, Set.of(11L), 2L, Set.of(21L, 23L), 3L, Set.of(31L)), true, HALF);
        assertThat(r.score()).isEqualByComparingTo("4");
        assertThat(r.maxScore()).isEqualByComparingTo("4");
        assertThat(r.correct()).isEqualTo(3);
    }

    @Test
    void multiCorrectNeedsTheExactSet() {
        var r = ScoreCalculator.grade(questions, Map.of(2L, Set.of(21L)), false, HALF);
        assertThat(r.score()).isEqualByComparingTo("0");
        assertThat(r.wrong()).isEqualTo(1);
        assertThat(r.unanswered()).isEqualTo(2);
    }

    @Test
    void negativeMarkingOffByDefaultMeansWrongEarnsZero() {
        var r = ScoreCalculator.grade(questions, Map.of(1L, Set.of(12L), 3L, Set.of(31L)), false, HALF);
        assertThat(r.score()).isEqualByComparingTo("1");
    }

    @Test
    void negativeMarkingDeductsHalfTheQuestionMarks() {
        // q1 correct (+1), q2 correct (+2), q3 wrong (-0.5 x 1)
        var r = ScoreCalculator.grade(questions,
                Map.of(1L, Set.of(11L), 2L, Set.of(21L, 23L), 3L, Set.of(32L)), true, HALF);
        assertThat(r.score()).isEqualByComparingTo("2.5");
    }

    @Test
    void unansweredIsNeverPenalised() {
        var r = ScoreCalculator.grade(questions, Map.of(1L, Set.of()), true, HALF);
        assertThat(r.score()).isEqualByComparingTo("0");
        assertThat(r.unanswered()).isEqualTo(3);
    }

    @Test
    void totalNeverGoesBelowZero() {
        var r = ScoreCalculator.grade(questions, Map.of(1L, Set.of(12L), 2L, Set.of(22L)), true, HALF);
        assertThat(r.score()).isEqualByComparingTo("0");
    }
}
