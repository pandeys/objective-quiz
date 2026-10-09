package org.sitare.quiz.bank;

import java.math.BigDecimal;
import java.util.List;

/** A validated question ready to be stored. Options are in display order. */
public record QuestionDraft(String topic, QuestionType type, String stem, BigDecimal marks,
                            List<OptionDraft> options) {

    public record OptionDraft(String text, boolean correct) {
    }
}
