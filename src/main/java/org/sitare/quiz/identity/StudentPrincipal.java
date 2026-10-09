package org.sitare.quiz.identity;

import java.io.Serializable;

/**
 * A student logged in for one quiz. The session token identifies this browser session;
 * a newer login for the same student replaces it (design A6).
 */
public record StudentPrincipal(long studentId, String rollNo, String name, long quizId, String sessionToken)
        implements Serializable {
}
