package ai.careerpilot.execution.retry;

import ai.careerpilot.domain.ApplicationRetry;
import ai.careerpilot.repo.ApplicationRetryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 2E.6 — exhaustive tests of the deterministic Retry & Recovery policy. The whole point of
 * this engine is that it can NEVER loop endlessly against an external site, so the failure-class ->
 * action matrix and the max-attempts cap are covered every which way.
 */
class RetryPolicyServiceTest {

    private final ApplicationRetryRepository repo = mock(ApplicationRetryRepository.class);
    private final RetryPolicyService policy = new RetryPolicyService(repo, new RetryMetrics(), 3, 1000L);

    // ── decide(): failure-class -> action, with attempts remaining (attempt 1 of max 3) ──

    @Test
    void networkRetriesWhileAttemptsRemain() {
        RetryDecision d = policy.decide(ApplicationRetry.CLASS_NETWORK, 1);
        assertThat(d.action()).isEqualTo(ApplicationRetry.ACTION_RETRY);
        assertThat(d.shouldRetry()).isTrue();
        assertThat(d.backoffMs()).isZero();
    }

    @Test
    void rateLimitedRetriesWithBackoffWhileAttemptsRemain() {
        RetryDecision d = policy.decide(ApplicationRetry.CLASS_RATE_LIMITED, 1);
        assertThat(d.action()).isEqualTo(ApplicationRetry.ACTION_RETRY_BACKOFF);
        assertThat(d.shouldRetry()).isTrue();
        assertThat(d.backoffMs()).isEqualTo(1000L); // base * 2^0
    }

    @Test
    void captchaAlwaysPauses() {
        RetryDecision d = policy.decide(ApplicationRetry.CLASS_CAPTCHA, 1);
        assertThat(d.action()).isEqualTo(ApplicationRetry.ACTION_PAUSE);
        assertThat(d.shouldRetry()).isFalse();
        assertThat(d.isTerminal()).isTrue();
    }

    @Test
    void loginFailureAlwaysPauses() {
        RetryDecision d = policy.decide(ApplicationRetry.CLASS_LOGIN_FAILED, 1);
        assertThat(d.action()).isEqualTo(ApplicationRetry.ACTION_PAUSE);
    }

    @Test
    void validationFailureAlwaysStops() {
        RetryDecision d = policy.decide(ApplicationRetry.CLASS_VALIDATION_FAILED, 1);
        assertThat(d.action()).isEqualTo(ApplicationRetry.ACTION_STOP);
        assertThat(d.shouldRetry()).isFalse();
    }

    @Test
    void duplicateAlwaysStops() {
        assertThat(policy.decide(ApplicationRetry.CLASS_DUPLICATE, 1).action())
                .isEqualTo(ApplicationRetry.ACTION_STOP);
    }

    @Test
    void unknownFailsClosedToStop() {
        assertThat(policy.decide(ApplicationRetry.CLASS_UNKNOWN, 1).action())
                .isEqualTo(ApplicationRetry.ACTION_STOP);
        assertThat(policy.decide(null, 1).action()).isEqualTo(ApplicationRetry.ACTION_STOP);
    }

    // ── Phase 7.16.1 — verification-specific failure classes ──

    @Test
    void confirmationMissingAlwaysPauses() {
        // Never auto-retried: the submission may have genuinely gone through and retrying risks
        // a real duplicate — this needs a human, same as CAPTCHA/LOGIN_FAILED.
        RetryDecision d = policy.decide(ApplicationRetry.CLASS_CONFIRMATION_MISSING, 1);
        assertThat(d.action()).isEqualTo(ApplicationRetry.ACTION_PAUSE);
        assertThat(d.shouldRetry()).isFalse();
    }

    @Test
    void atsErrorRetriesWhileAttemptsRemainThenStops() {
        assertThat(policy.decide(ApplicationRetry.CLASS_ATS_ERROR, 1).action()).isEqualTo(ApplicationRetry.ACTION_RETRY);
        assertThat(policy.decide(ApplicationRetry.CLASS_ATS_ERROR, 3).action()).isEqualTo(ApplicationRetry.ACTION_STOP);
    }

    @Test
    void browserFailureRetriesWhileAttemptsRemainThenStops() {
        assertThat(policy.decide(ApplicationRetry.CLASS_BROWSER_FAILURE, 1).action()).isEqualTo(ApplicationRetry.ACTION_RETRY);
        assertThat(policy.decide(ApplicationRetry.CLASS_BROWSER_FAILURE, 3).action()).isEqualTo(ApplicationRetry.ACTION_STOP);
    }

    @Test
    void providerFailureRetriesWhileAttemptsRemainThenStops() {
        assertThat(policy.decide(ApplicationRetry.CLASS_PROVIDER_FAILURE, 1).action()).isEqualTo(ApplicationRetry.ACTION_RETRY);
        assertThat(policy.decide(ApplicationRetry.CLASS_PROVIDER_FAILURE, 3).action()).isEqualTo(ApplicationRetry.ACTION_STOP);
    }

    @ParameterizedTest
    @CsvSource({
            "no post-submit page content was captured,CONFIRMATION_MISSING",
            "unable to verify submission,CONFIRMATION_MISSING",
            "ATS error during verification,ATS_ERROR",
            "playwright crashed unexpectedly,BROWSER_FAILURE",
            "browser context closed unexpectedly,BROWSER_FAILURE",
            "connector failed during verifySubmission,PROVIDER_FAILURE"
    })
    void classifiesVerificationFailureReasonsByKeyword(String reason, String expectedClass) {
        assertThat(policy.classify(reason)).isEqualTo(expectedClass);
    }

    // ── max-attempts cap: a would-be RETRY becomes STOP once attempts are exhausted ──

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 5, 99})
    void networkStopsOnceAttemptsExhausted(int attempt) {
        assertThat(policy.decide(ApplicationRetry.CLASS_NETWORK, attempt).action())
                .isEqualTo(ApplicationRetry.ACTION_STOP);
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 10})
    void rateLimitedStopsOnceAttemptsExhausted(int attempt) {
        assertThat(policy.decide(ApplicationRetry.CLASS_RATE_LIMITED, attempt).action())
                .isEqualTo(ApplicationRetry.ACTION_STOP);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void networkRetriesForAttemptsBelowMax(int attempt) {
        assertThat(policy.decide(ApplicationRetry.CLASS_NETWORK, attempt).action())
                .isEqualTo(ApplicationRetry.ACTION_RETRY);
    }

    @Test
    void backoffGrowsExponentially() {
        assertThat(policy.decide(ApplicationRetry.CLASS_RATE_LIMITED, 1).backoffMs()).isEqualTo(1000L);
        assertThat(policy.decide(ApplicationRetry.CLASS_RATE_LIMITED, 2).backoffMs()).isEqualTo(2000L);
        // attempt 3 is at the cap -> STOP, so backoff is irrelevant (0)
        assertThat(policy.decide(ApplicationRetry.CLASS_RATE_LIMITED, 3).backoffMs()).isZero();
    }

    // ── classify(): keyword -> class ──

    @ParameterizedTest
    @CsvSource({
            "Connection timeout while loading,NETWORK",
            "socket connection reset,NETWORK",
            "host unreachable,NETWORK",
            "reCAPTCHA challenge presented,CAPTCHA",
            "solve the captcha,CAPTCHA",
            "401 Unauthorized,LOGIN_FAILED",
            "invalid credentials,LOGIN_FAILED",
            "authentication required,LOGIN_FAILED",
            "You have already applied to this role,DUPLICATE",
            "duplicate submission detected,DUPLICATE",
            "HTTP 429 too many requests,RATE_LIMITED",
            "rate limit exceeded,RATE_LIMITED",
            "form validation failed,VALIDATION_FAILED",
            "required field missing,VALIDATION_FAILED",
            "something totally weird,UNKNOWN"
    })
    void classifiesFailureReasonsByKeyword(String reason, String expectedClass) {
        assertThat(policy.classify(reason)).isEqualTo(expectedClass);
    }

    @Test
    void classifyHandlesNullAndBlank() {
        assertThat(policy.classify(null)).isEqualTo(ApplicationRetry.CLASS_UNKNOWN);
        assertThat(policy.classify("")).isEqualTo(ApplicationRetry.CLASS_UNKNOWN);
    }

    // ── handleFailure(): classify + decide + persist + never throw ──

    @Test
    void handleFailurePersistsARetryRowAndReturnsDecision() {
        when(repo.save(org.mockito.ArgumentMatchers.any(ApplicationRetry.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        RetryDecision d = policy.handleFailure(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, "connection timeout");
        assertThat(d.failureClass()).isEqualTo(ApplicationRetry.CLASS_NETWORK);
        assertThat(d.action()).isEqualTo(ApplicationRetry.ACTION_RETRY);
    }

    @Test
    void handleFailureNeverThrowsEvenIfPersistFails() {
        when(repo.save(org.mockito.ArgumentMatchers.any(ApplicationRetry.class)))
                .thenThrow(new RuntimeException("db down"));
        RetryDecision d = policy.handleFailure(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, "429 too many requests");
        assertThat(d.action()).isEqualTo(ApplicationRetry.ACTION_RETRY_BACKOFF);
    }

    @Test
    void maxAttemptsIsExposed() {
        assertThat(policy.maxAttempts()).isEqualTo(3);
    }
}
