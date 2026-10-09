package org.sitare.quiz.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Formatting helpers for templates, used as {@code ${@fmt.dateTime(x)}}. All times are shown in the campus time zone. */
@Component("fmt")
public class Fmt {

    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy, h:mm a");
    private static final DateTimeFormatter INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final ZoneId zone;

    public Fmt(@Value("${app.timezone:Asia/Kolkata}") String zone) {
        this.zone = ZoneId.of(zone);
    }

    public ZoneId zone() {
        return zone;
    }

    public String dateTime(Instant instant) {
        return instant == null ? "" : DISPLAY.format(instant.atZone(zone));
    }

    /** Value for an HTML datetime-local input. */
    public String input(Instant instant) {
        return instant == null ? "" : INPUT.format(instant.atZone(zone));
    }

    public Instant parseInput(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return LocalDateTime.parse(text.trim(), INPUT).atZone(zone).toInstant();
    }

    public String marks(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }
}
