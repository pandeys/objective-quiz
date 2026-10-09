package org.sitare.quiz.bank;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class QuestionRowParserTest {

    private static final List<String> HEADER =
            List.of("topic", "type", "question", "marks", "correct", "option1", "option2", "option3", "option4");

    @Test
    void parsesSingleMultiAndTrueFalse() {
        var result = QuestionRowParser.parse(List.of(
                HEADER,
                List.of("OOP", "SINGLE", "Which keyword?", "1", "3", "static", "private", "final", "abstract"),
                List.of("OOP", "MULTI", "Valid modifiers?", "2", "1;2", "public", "protected", "friend", ""),
                List.of("Exceptions", "TRUE_FALSE", "Checked must be declared.", "", "true", "", "", "", "")));

        assertThat(result.errors()).isEmpty();
        assertThat(result.questions()).hasSize(3);
        assertThat(result.questions().get(0).options()).extracting(QuestionDraft.OptionDraft::correct)
                .containsExactly(false, false, true, false);
        assertThat(result.questions().get(1).options()).hasSize(3);
        assertThat(result.questions().get(2).options()).extracting(QuestionDraft.OptionDraft::text)
                .containsExactly("True", "False");
        assertThat(result.questions().get(2).options().get(0).correct()).isTrue();
    }

    @Test
    void reportsEveryBadRowWithItsLineNumber() {
        var result = QuestionRowParser.parse(List.of(
                HEADER,
                List.of("OOP", "SINGLE", "", "1", "1", "a", "b", "", ""),
                List.of("OOP", "SINGLE", "Two correct?", "1", "1;2", "a", "b", "", ""),
                List.of("OOP", "SINGLE", "Out of range", "1", "5", "a", "b", "", ""),
                List.of("OOP", "ESSAY", "Bad type", "1", "1", "a", "b", "", "")));

        assertThat(result.questions()).hasSize(0);
        assertThat(result.errors()).hasSize(4);
        assertThat(result.errors().get(0)).startsWith("Row 2:");
        assertThat(result.errors().get(3)).contains("SINGLE, MULTI or TRUE_FALSE");
    }

    @Test
    void requiresHeaderColumns() {
        var result = QuestionRowParser.parse(List.of(List.of("foo", "bar"), List.of("1", "2")));
        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("'question'"));
    }
}
