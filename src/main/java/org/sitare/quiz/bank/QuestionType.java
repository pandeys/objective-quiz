package org.sitare.quiz.bank;

public enum QuestionType {
    /** Exactly one correct option. */
    SINGLE,
    /** One or more correct options; the student must select exactly the correct set. */
    MULTI,
    /** Two options, True and False. */
    TRUE_FALSE;

    public boolean singleChoice() {
        return this != MULTI;
    }
}
