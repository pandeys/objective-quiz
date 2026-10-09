package org.sitare.quiz.bank;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public record Question(long id, long facultyId, String topic, QuestionType type, String stem, BigDecimal marks,
                       boolean active, List<Option> options) {

    public record Option(long id, String text, boolean correct, int position) {
    }

    public Set<Long> correctOptionIds() {
        return options.stream().filter(Option::correct).map(Option::id).collect(Collectors.toSet());
    }

    public Set<Long> optionIds() {
        return options.stream().map(Option::id).collect(Collectors.toSet());
    }
}
