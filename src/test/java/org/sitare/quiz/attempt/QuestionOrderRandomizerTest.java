package org.sitare.quiz.attempt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class QuestionOrderRandomizerTest {

    private static Map<Long, List<Long>> quiz() {
        Map<Long, List<Long>> m = new LinkedHashMap<>();
        for (long q = 1; q <= 20; q++) {
            m.put(q, List.of(q * 10 + 1, q * 10 + 2, q * 10 + 3, q * 10 + 4));
        }
        return m;
    }

    @Test
    void sameStudentAlwaysGetsTheSameOrder() {
        assertThat(QuestionOrderRandomizer.shuffle(7, 42, quiz()))
                .containsExactlyEntriesOf(QuestionOrderRandomizer.shuffle(7, 42, quiz()));
        assertThat(new ArrayList<>(QuestionOrderRandomizer.shuffle(7, 42, quiz()).keySet()))
                .isEqualTo(new ArrayList<>(QuestionOrderRandomizer.shuffle(7, 42, quiz()).keySet()));
    }

    @Test
    void differentStudentsGetDifferentOrders() {
        var a = new ArrayList<>(QuestionOrderRandomizer.shuffle(7, 1, quiz()).keySet());
        var b = new ArrayList<>(QuestionOrderRandomizer.shuffle(7, 2, quiz()).keySet());
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void keepsEveryQuestionAndOption() {
        var shuffled = QuestionOrderRandomizer.shuffle(3, 9, quiz());
        assertThat(shuffled.keySet()).containsExactlyInAnyOrderElementsOf(quiz().keySet());
        shuffled.forEach((q, options) -> assertThat(options).containsExactlyInAnyOrderElementsOf(quiz().get(q)));
    }
}
