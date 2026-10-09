package org.sitare.quiz.quiz;

import java.math.BigDecimal;
import java.time.Instant;

public record Quiz(
        long id,
        long facultyId,
        String code,
        String title,
        String course,
        Instant startsAt,
        int durationMin,
        int lateJoinMin,
        int extensionMin,
        boolean negativeMarking,
        BigDecimal negativeFraction,
        boolean webcamEnabled,
        int snapshotRetentionDays,
        boolean answersShared,
        QuizStatus status) {

    /** The last moment a student may start an attempt. */
    public Instant lateJoinUntil() {
        return startsAt.plusSeconds(60L * (lateJoinMin + extensionMin));
    }

    /** The latest any regular deadline can be (before per-student extra minutes). */
    public Instant hardEnd() {
        return startsAt.plusSeconds(60L * (durationMin + lateJoinMin + extensionMin));
    }
}
