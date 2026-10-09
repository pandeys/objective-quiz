package org.sitare.quiz.quiz;

/**
 * Lets the quiz module change running attempts (extensions, closing a quiz) without depending on the
 * attempt module's internals. Implemented by the attempt module.
 */
public interface AttemptAdjuster {

    void extendAll(long quizId, int minutes);

    void extendOne(long quizId, long studentId, int minutes);

    void submitAllOpen(long quizId);
}
