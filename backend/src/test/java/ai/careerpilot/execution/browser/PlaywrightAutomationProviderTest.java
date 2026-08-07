package ai.careerpilot.execution.browser;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Gap D — unit tests for {@link PlaywrightAutomationProvider} that do NOT launch a real browser
 * (not possible in this environment/CI). {@link BrowserSessionManager} is mocked out entirely, so
 * these tests cover: configuration/flag semantics, the mandatory "login always throws" safety
 * boundary (guest-apply-only, never any credentialed automation), and the "no active page" guard
 * that every page-driving method relies on before {@link PlaywrightAutomationProvider#navigate}
 * has ever been called on the current thread. Real navigation/fill/screenshot behavior against a
 * live page is an integration-only concern, out of scope here — see {@link CaptchaLoginDetectorTest}
 * for the risk-relevant detection logic, which IS fully unit tested.
 */
class PlaywrightAutomationProviderTest {

    private static final long NETWORK_IDLE_BUDGET_MS = 4000;

    private final BrowserAutomationMetrics metrics = new BrowserAutomationMetrics();

    private ai.careerpilot.execution.browser.pool.BrowserLeasePool leasePool =
            mock(ai.careerpilot.execution.browser.pool.BrowserLeasePool.class);

    private PlaywrightAutomationProvider provider(boolean flagEnabled, boolean sessionManagerEnabled) {
        BrowserSessionManager sessionManager = mock(BrowserSessionManager.class);
        when(sessionManager.isEnabled()).thenReturn(sessionManagerEnabled);
        leasePool = mock(ai.careerpilot.execution.browser.pool.BrowserLeasePool.class);
        return new PlaywrightAutomationProvider(sessionManager, leasePool, metrics, flagEnabled,
                NETWORK_IDLE_BUDGET_MS, true);
    }

    /** Same shape, but with the adaptive settle poll turned off (the documented revert path). */
    private PlaywrightAutomationProvider providerWithFixedSettle() {
        BrowserSessionManager sessionManager = mock(BrowserSessionManager.class);
        when(sessionManager.isEnabled()).thenReturn(true);
        leasePool = mock(ai.careerpilot.execution.browser.pool.BrowserLeasePool.class);
        return new PlaywrightAutomationProvider(sessionManager, leasePool, metrics, true,
                NETWORK_IDLE_BUDGET_MS, false);
    }

    @Test
    void nameIsPlaywright() {
        assertThat(provider(false, false).name()).isEqualTo("playwright");
    }

    @Test
    void isConfiguredRequiresBothFlagAndSessionManager() {
        assertThat(provider(false, false).isConfigured()).isFalse();
        assertThat(provider(true, false).isConfigured()).isFalse();
        assertThat(provider(false, true).isConfigured()).isFalse();
        assertThat(provider(true, true).isConfigured()).isTrue();
    }

    @Test
    void flagEnabledReflectsOnlyTheFlag() {
        assertThat(provider(false, true).flagEnabled()).isFalse();
        assertThat(provider(true, false).flagEnabled()).isTrue();
    }

    @Test
    void loginAlwaysThrows_guestApplyOnlySafetyBoundary() {
        // SAFETY-CRITICAL: never performed regardless of flags/configuration — no credential is
        // ever stored, entered, or handled for any ATS/employer portal.
        PlaywrightAutomationProvider configured = provider(true, true);
        assertThatThrownBy(() -> configured.login("https://portal.example.com", Map.of("u", "x", "p", "y")))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("guest-apply-only");

        PlaywrightAutomationProvider unconfigured = provider(false, false);
        assertThatThrownBy(() -> unconfigured.login("https://portal.example.com", Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void navigateThrowsWhenNotConfigured() {
        assertThatThrownBy(() -> provider(false, false).navigate("https://example.com"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pageDrivingMethodsThrowWithoutAPriorNavigate() {
        PlaywrightAutomationProvider p = provider(true, true);
        assertThatThrownBy(() -> p.fillForm(Map.of("#x", "y"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(p::submit).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> p.captureScreenshot(Path.of("s.png"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(p::captureConfirmation).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(p::currentPageHtml).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void answerQuestionsIsANoOp_neverFabricatesAnswers() {
        PlaywrightAutomationProvider p = provider(true, true);
        // Must not throw even without an active page — this build never answers free-text
        // screening questions (answers must never be fabricated).
        p.answerQuestions(Map.of("Why do you want this job?", "n/a"));
    }

    @Test
    void logoutIsSafeToCallWithoutAnyPriorNavigate() {
        PlaywrightAutomationProvider p = provider(true, true);
        p.logout(); // must not throw
    }

    // ── Enterprise Browser Automation — lease lifecycle ──

    private ai.careerpilot.execution.browser.pool.ContextLease stubLease() {
        var lease = mock(ai.careerpilot.execution.browser.pool.ContextLease.class);
        when(lease.id()).thenReturn(java.util.UUID.randomUUID());
        when(lease.page()).thenReturn(mock(com.microsoft.playwright.Page.class));
        return lease;
    }

    @Test
    void navigateAcquiresALeaseAndLogoutReleasesIt() {
        PlaywrightAutomationProvider p = provider(true, true);
        var lease = stubLease();
        when(leasePool.acquire()).thenReturn(lease);

        p.navigate("https://example.com");
        org.mockito.Mockito.verify(leasePool).acquire();

        p.logout();
        org.mockito.Mockito.verify(lease).close();
    }

    /**
     * The leak this refactor fixes. Previously {@code navigate()} overwrote the thread's context
     * holder unconditionally, so a caller that threw before {@code logout()} orphaned a live
     * Chromium context on a pooled thread — invisible, and permanently consuming memory.
     */
    @Test
    void navigateReleasesAnUnclosedPriorLeaseInsteadOfOrphaningIt() {
        PlaywrightAutomationProvider p = provider(true, true);
        var first = stubLease();
        var second = stubLease();
        when(leasePool.acquire()).thenReturn(first, second);

        p.navigate("https://example.com/one");
        // No logout() — simulates a caller that threw mid-session on a pooled thread.
        p.navigate("https://example.com/two");

        org.mockito.Mockito.verify(first).close();
        org.mockito.Mockito.verify(leasePool, org.mockito.Mockito.times(2)).acquire();
    }

    @Test
    void navigateFailureReleasesTheLeaseRatherThanHoldingCapacity() {
        PlaywrightAutomationProvider p = provider(true, true);
        var lease = mock(ai.careerpilot.execution.browser.pool.ContextLease.class);
        when(lease.id()).thenReturn(java.util.UUID.randomUUID());
        com.microsoft.playwright.Page page = mock(com.microsoft.playwright.Page.class);
        when(lease.page()).thenReturn(page);
        org.mockito.Mockito.doThrow(new IllegalStateException("navigation failed"))
                .when(page).navigate(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
        when(leasePool.acquire()).thenReturn(lease);

        assertThatThrownBy(() -> p.navigate("https://example.com"))
                .isInstanceOf(IllegalStateException.class);

        org.mockito.Mockito.verify(lease).close();
    }

    @Test
    void capacityExhaustionPropagatesAsARetryableSignalNotAnOom() {
        PlaywrightAutomationProvider p = provider(true, true);
        when(leasePool.acquire()).thenThrow(
                new ai.careerpilot.execution.browser.pool.BrowserLeasePool
                        .BrowserCapacityUnavailableException("no browser capacity within 30s"));

        assertThatThrownBy(() -> p.navigate("https://example.com"))
                .isInstanceOf(ai.careerpilot.execution.browser.pool.BrowserLeasePool
                        .BrowserCapacityUnavailableException.class)
                .hasMessageContaining("no browser capacity");
    }

    // ── P3 — navigation performance: bounded waits and the adaptive settle window ──
    //
    // Measured against live ATS pages before this change: a Lever apply form never reaches
    // network-idle at all, so the previously-unbounded wait burned the full 30,006 ms page-default
    // timeout on EVERY validation of it. These tests pin the two properties that fix stopped it
    // being 30s without changing what discovery sees.

    /** A lease whose page reports a control count sequence, so settle behaviour is observable. */
    private ai.careerpilot.execution.browser.pool.ContextLease leaseReporting(Integer... controlCounts) {
        var page = mock(com.microsoft.playwright.Page.class);
        when(page.evaluate(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(controlCounts[0], java.util.Arrays.copyOfRange(
                        (Object[]) controlCounts, 1, controlCounts.length));
        var lease = mock(ai.careerpilot.execution.browser.pool.ContextLease.class);
        when(lease.id()).thenReturn(java.util.UUID.randomUUID());
        when(lease.page()).thenReturn(page);
        return lease;
    }

    @Test
    void navigateWaitsOnlyForDomContentLoaded_notTheRedundantLoadEvent() {
        PlaywrightAutomationProvider p = provider(true, true);
        var lease = stubLease();
        when(leasePool.acquire()).thenReturn(lease);

        p.navigate("https://example.com");

        var captor = org.mockito.ArgumentCaptor.forClass(com.microsoft.playwright.Page.NavigateOptions.class);
        org.mockito.Mockito.verify(lease.page())
                .navigate(org.mockito.ArgumentMatchers.eq("https://example.com"), captor.capture());
        assertThat(captor.getValue().waitUntil)
                .isEqualTo(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED);
    }

    @Test
    void networkIdleWaitIsBounded_soAPageThatNeverGoesIdleCostsSecondsNotThirtySeconds() {
        PlaywrightAutomationProvider p = provider(true, true);
        var lease = leaseReporting(5, 5, 5);
        when(leasePool.acquire()).thenReturn(lease);
        p.navigate("https://example.com");

        p.waitForStable(1500);

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.microsoft.playwright.Page.WaitForLoadStateOptions.class);
        org.mockito.Mockito.verify(lease.page()).waitForLoadState(
                org.mockito.ArgumentMatchers.eq(com.microsoft.playwright.options.LoadState.NETWORKIDLE),
                captor.capture());
        assertThat(captor.getValue().timeout).isEqualTo((double) NETWORK_IDLE_BUDGET_MS);
    }

    @Test
    void adaptiveSettleReturnsEarlyOnceTheControlCountIsStableAndNonZero() {
        PlaywrightAutomationProvider p = provider(true, true);
        // 12 == 12 == 12: stable and non-zero, so the poll exits well inside its 1500ms budget.
        var lease = leaseReporting(12, 12, 12, 12, 12, 12, 12, 12);
        when(leasePool.acquire()).thenReturn(lease);
        p.navigate("https://example.com");

        long start = System.currentTimeMillis();
        p.waitForStable(1500);
        long elapsed = System.currentTimeMillis() - start;

        // The mocked page's waitForTimeout does nothing, so wall-clock only proves the loop
        // terminated; the real assertion is that it stopped polling rather than running the
        // full budget's worth of 250ms samples.
        assertThat(elapsed).isLessThan(1500);
        org.mockito.Mockito.verify(lease.page(), org.mockito.Mockito.atMost(4))
                .evaluate(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void adaptiveSettleNeverReturnsEarlyOnAZeroControlPage_theUnhydratedSpaCase() {
        PlaywrightAutomationProvider p = provider(true, true);
        // Zero controls is exactly the un-hydrated <div id="app"> state the settle window exists
        // to outlast. A stable zero must NOT be treated as settled.
        var lease = leaseReporting(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        when(leasePool.acquire()).thenReturn(lease);
        p.navigate("https://example.com");

        p.waitForStable(1000);

        // Polled repeatedly instead of exiting after two equal samples.
        org.mockito.Mockito.verify(lease.page(), org.mockito.Mockito.atLeast(3))
                .evaluate(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void adaptiveSettleCanBeTurnedOff_revertingToTheFixedSleep() {
        PlaywrightAutomationProvider p = providerWithFixedSettle();
        var lease = leaseReporting(9, 9, 9);
        when(leasePool.acquire()).thenReturn(lease);
        p.navigate("https://example.com");

        p.waitForStable(1500);

        org.mockito.Mockito.verify(lease.page(), org.mockito.Mockito.never())
                .evaluate(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(lease.page()).waitForTimeout(1500);
    }

    @Test
    void settlePollFailureFallsBackToWaitingRatherThanReturningEarly() {
        PlaywrightAutomationProvider p = provider(true, true);
        var page = mock(com.microsoft.playwright.Page.class);
        when(page.evaluate(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException("frame detached mid-poll"));
        var lease = mock(ai.careerpilot.execution.browser.pool.ContextLease.class);
        when(lease.id()).thenReturn(java.util.UUID.randomUUID());
        when(lease.page()).thenReturn(page);
        when(leasePool.acquire()).thenReturn(lease);
        p.navigate("https://example.com");

        p.waitForStable(1200); // must not throw

        org.mockito.Mockito.verify(page).waitForTimeout(org.mockito.ArgumentMatchers.anyDouble());
    }

    // ── P4 WI1 — submit is issued exactly once, ever ──────────────────────────────────────────
    //
    // Every other operation on this provider is idempotent, so withRetry is free. Submitting an
    // application is the one irreversible outward-facing action, and it was the one being retried.
    // A click that triggers navigation routinely leaves Playwright throwing AFTER the form was
    // already submitted; the retry then clicked again, sending a second real application.

    @Test
    void submitClicksExactlyOnce() {
        PlaywrightAutomationProvider p = provider(true, true);
        var lease = stubLease();
        when(leasePool.acquire()).thenReturn(lease);
        p.navigate("https://example.com");

        p.submit();

        org.mockito.Mockito.verify(lease.page(), org.mockito.Mockito.times(1))
                .click(org.mockito.ArgumentMatchers.eq("button[type=submit]"));
    }

    @Test
    void submitDoesNotRetryWhenTheBrowserThrowsAfterTheClick() {
        PlaywrightAutomationProvider p = provider(true, true);
        var lease = stubLease();
        when(leasePool.acquire()).thenReturn(lease);
        p.navigate("https://example.com");
        // Hoisted: lease.page() is itself a stubbed call and cannot be evaluated inside when().
        com.microsoft.playwright.Page page = lease.page();
        org.mockito.Mockito.doThrow(new RuntimeException("Element is not attached to the DOM"))
                .when(page).click(org.mockito.ArgumentMatchers.anyString());

        assertThatThrownBy(p::submit).isInstanceOf(RuntimeException.class);

        // ONE click. The exception means "delivery unknown", never "click failed, try again".
        org.mockito.Mockito.verify(page, org.mockito.Mockito.times(1))
                .click(org.mockito.ArgumentMatchers.eq("button[type=submit]"));
    }

    @Test
    void idempotentOperationsAreStillRetried() {
        // The contrast that makes the submit rule a rule and not an accident: filling a field is
        // safe to repeat, so it keeps its retry.
        PlaywrightAutomationProvider p = provider(true, true);
        var lease = stubLease();
        when(leasePool.acquire()).thenReturn(lease);
        p.navigate("https://example.com");
        com.microsoft.playwright.Page page = lease.page();
        org.mockito.Mockito.doThrow(new RuntimeException("transient"))
                .when(page).fill(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());

        assertThatThrownBy(() -> p.fillForm(java.util.Map.of("#a", "b")))
                .isInstanceOf(RuntimeException.class);

        org.mockito.Mockito.verify(page, org.mockito.Mockito.atLeast(2))
                .fill(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    // ── P4 WI5 — the console buffer must not survive the request thread ───────────────────────

    @Test
    void clearConsoleCaptureUnbindsTheBufferFromTheThread() {
        PlaywrightAutomationProvider p = provider(true, true);
        var lease = stubLease();
        when(leasePool.acquire()).thenReturn(lease);
        p.navigate("https://example.com");
        p.startConsoleCapture();

        p.clearConsoleCapture();   // must not throw, and must be safe to call twice
        p.clearConsoleCapture();

        // A fresh get() after removal starts empty rather than returning the previous run's text.
        assertThat(p.drainConsoleErrors()).isEmpty();
    }

    @Test
    void clearConsoleCaptureIsSafeWithoutAnyPriorCapture() {
        PlaywrightAutomationProvider p = provider(true, true);
        p.clearConsoleCapture();   // no navigate, no capture started
    }
}
