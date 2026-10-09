package org.sitare.quiz.quiz;

import java.math.BigDecimal;
import java.time.Instant;

/** Fields faculty set when creating or editing a quiz. */
public record QuizSettings(
        String title,
        String course,
        Instant startsAt,
        int durationMin,
        int lateJoinMin,
        boolean negativeMarking,
        BigDecimal negativeFraction,
        boolean webcamEnabled,
        int snapshotRetentionDays) {
}
