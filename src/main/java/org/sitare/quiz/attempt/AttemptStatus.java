package org.sitare.quiz.attempt;

public enum AttemptStatus {
    IN_PROGRESS,
    SUBMITTED,
    AUTO_SUBMITTED;

    public boolean finished() {
        return this != IN_PROGRESS;
    }
}
