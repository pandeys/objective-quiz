package org.sitare.quiz.attempt;

import java.time.Clock;
import java.time.Instant;

import org.sitare.quiz.common.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Submits attempts whose deadline has passed, including students who went offline (design A3). */
@Component
public class AutoSubmitJob {

    private static final Logger log = LoggerFactory.getLogger(AutoSubmitJob.class);

    private final AttemptRepository attempts;
    private final AttemptService service;
    private final Clock clock;
    private final int graceSeconds;

    public AutoSubmitJob(AttemptRepository attempts, AttemptService service, Clock clock, AppProperties props) {
        this.attempts = attempts;
        this.service = service;
        this.clock = clock;
        this.graceSeconds = props.quiz() == null ? 5 : props.quiz().graceSeconds();
    }

    @Scheduled(fixedDelayString = "${app.quiz.auto-submit-interval-ms:10000}")
    public void run() {
        Instant cutoff = clock.instant().minusSeconds(graceSeconds);
        for (Long id : attempts.overdueIds(cutoff, 200)) {
            try {
                service.finish(id, AttemptStatus.AUTO_SUBMITTED);
            } catch (RuntimeException e) {
                log.error("Auto-submit failed for attempt {}", id, e);
            }
        }
    }
}
