package org.sitare.quiz.web.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.sitare.quiz.attempt.AttemptService;
import org.sitare.quiz.attempt.AttemptView;
import org.sitare.quiz.identity.StudentPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The student's own attempt. The attempt is always taken from the login, never from an id in the URL. */
@RestController
@RequestMapping("/api/attempt")
public class AttemptApiController {

    public record SaveRequest(@Size(max = 10) List<Long> selectedOptionIds, boolean markedForReview,
                              @NotNull Long clientSeq) {
    }

    private final AttemptService attempts;

    public AttemptApiController(AttemptService attempts) {
        this.attempts = attempts;
    }

    @PostMapping("/start")
    public AttemptView start(@AuthenticationPrincipal StudentPrincipal me) {
        return attempts.start(me);
    }

    @GetMapping
    public AttemptView get(@AuthenticationPrincipal StudentPrincipal me) {
        return attempts.view(me);
    }

    @PutMapping("/answers/{questionId}")
    public AttemptService.SaveResult save(@AuthenticationPrincipal StudentPrincipal me,
                                          @PathVariable long questionId,
                                          @Valid @RequestBody SaveRequest body) {
        return attempts.saveAnswer(me, questionId, body.selectedOptionIds(), body.markedForReview(), body.clientSeq());
    }

    @PostMapping("/heartbeat")
    public AttemptService.Heartbeat heartbeat(@AuthenticationPrincipal StudentPrincipal me) {
        return attempts.heartbeat(me);
    }

    @PostMapping("/submit")
    public AttemptView submit(@AuthenticationPrincipal StudentPrincipal me) {
        return attempts.submit(me);
    }
}
