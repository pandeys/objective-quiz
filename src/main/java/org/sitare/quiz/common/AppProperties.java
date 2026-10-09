package org.sitare.quiz.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Bootstrap bootstrap, Quiz quiz) {

    public record Bootstrap(String facultyEmail, String facultyName, String facultyPassword) {
    }

    public record Quiz(int graceSeconds, long autoSubmitIntervalMs) {
    }
}
