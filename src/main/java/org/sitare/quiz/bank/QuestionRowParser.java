package org.sitare.quiz.bank;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns spreadsheet rows into questions, collecting a readable error per bad row.
 *
 * <p>Expected columns (header row required, names case-insensitive):
 * {@code topic, type, question, marks, correct, option1 ... option6}.
 * {@code type} is SINGLE, MULTI or TRUE_FALSE (blank means SINGLE).
 * {@code correct} lists 1-based option numbers separated by ';' (for example {@code 2} or {@code 1;3}).
 * For TRUE_FALSE the options may be left blank and {@code correct} may be {@code true} or {@code false}.
 */
public final class QuestionRowParser {

    public static final int MAX_OPTIONS = 6;
    static final List<String> REQUIRED = List.of("question", "correct");

    public record ParseResult(List<QuestionDraft> questions, List<String> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    private QuestionRowParser() {
    }

    public static ParseResult parse(List<List<String>> rows) {
        List<QuestionDraft> questions = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (rows.isEmpty()) {
            errors.add("The file is empty.");
            return new ParseResult(questions, errors);
        }

        List<String> header = rows.get(0).stream().map(h -> h == null ? "" : h.trim().toLowerCase(Locale.ROOT)).toList();
        for (String required : REQUIRED) {
            if (!header.contains(required)) {
                errors.add("Header row is missing the column '" + required + "'.");
            }
        }
        if (!errors.isEmpty()) {
            return new ParseResult(questions, errors);
        }

        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.stream().allMatch(c -> c == null || c.isBlank())) {
                continue;
            }
            int line = i + 1;
            try {
                questions.add(parseRow(header, row));
            } catch (IllegalArgumentException e) {
                errors.add("Row " + line + ": " + e.getMessage());
            }
        }
        if (questions.isEmpty() && errors.isEmpty()) {
            errors.add("No questions found below the header row.");
        }
        return new ParseResult(questions, errors);
    }

    static QuestionDraft parseRow(List<String> header, List<String> row) {
        String topic = cell(header, row, "topic");
        String typeText = cell(header, row, "type");
        String stem = cell(header, row, "question");
        String marksText = cell(header, row, "marks");
        String correctText = cell(header, row, "correct");

        if (stem.isEmpty()) {
            throw new IllegalArgumentException("question text is empty.");
        }
        QuestionType type;
        try {
            type = typeText.isEmpty() ? QuestionType.SINGLE
                    : QuestionType.valueOf(typeText.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_'));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("type must be SINGLE, MULTI or TRUE_FALSE, not '" + typeText + "'.");
        }

        BigDecimal marks;
        try {
            marks = marksText.isEmpty() ? BigDecimal.ONE : new BigDecimal(marksText);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("marks must be a number, not '" + marksText + "'.");
        }
        if (marks.signum() <= 0) {
            throw new IllegalArgumentException("marks must be greater than 0.");
        }

        List<String> optionTexts = new ArrayList<>();
        for (int n = 1; n <= MAX_OPTIONS; n++) {
            String text = cell(header, row, "option" + n);
            if (!text.isEmpty()) {
                optionTexts.add(text);
            }
        }

        if (type == QuestionType.TRUE_FALSE) {
            if (optionTexts.isEmpty()) {
                optionTexts = List.of("True", "False");
            }
            if (correctText.equalsIgnoreCase("true")) {
                correctText = "1";
            } else if (correctText.equalsIgnoreCase("false")) {
                correctText = "2";
            }
            if (optionTexts.size() != 2) {
                throw new IllegalArgumentException("a TRUE_FALSE question must have exactly 2 options.");
            }
        } else if (optionTexts.size() < 2) {
            throw new IllegalArgumentException("at least 2 options are needed.");
        }

        Set<Integer> correct = parseCorrect(correctText, optionTexts.size());
        if (type.singleChoice() && correct.size() != 1) {
            throw new IllegalArgumentException(type + " questions need exactly one correct option.");
        }

        List<QuestionDraft.OptionDraft> options = new ArrayList<>();
        for (int n = 0; n < optionTexts.size(); n++) {
            options.add(new QuestionDraft.OptionDraft(optionTexts.get(n), correct.contains(n + 1)));
        }
        return new QuestionDraft(topic.isEmpty() ? "General" : topic, type, stem, marks, List.copyOf(options));
    }

    static Set<Integer> parseCorrect(String text, int optionCount) {
        if (text.isEmpty()) {
            throw new IllegalArgumentException("'correct' is empty.");
        }
        Set<Integer> result = new LinkedHashSet<>();
        for (String part : text.split("[;,|\\s]+")) {
            if (part.isBlank()) {
                continue;
            }
            int n;
            try {
                n = Integer.parseInt(part.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'correct' must list option numbers like 2 or 1;3, not '" + text + "'.");
            }
            if (n < 1 || n > optionCount) {
                throw new IllegalArgumentException("correct option " + n + " does not exist (there are " + optionCount + " options).");
            }
            result.add(n);
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("'correct' is empty.");
        }
        return result;
    }

    private static String cell(List<String> header, List<String> row, String name) {
        int idx = header.indexOf(name);
        if (idx < 0 || idx >= row.size() || row.get(idx) == null) {
            return "";
        }
        return row.get(idx).trim();
    }
}
