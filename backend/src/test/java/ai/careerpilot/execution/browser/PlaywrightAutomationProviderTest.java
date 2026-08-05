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

    private final BrowserAutomationMetrics metrics = new BrowserAutomationMetrics();

    private ai.careerpilot.execution.browser.pool.BrowserLeasePool leasePool =
            mock(ai.careerpilot.execution.browser.pool.BrowserLeasePool.class);

    private PlaywrightAutomationProvider provider(boolean flagEnabled, boolean sessionManagerEnabled) {
        BrowserSessionManager sessionManager = mock(BrowserSessionManager.class);
        when(sessionManager.isEnabled()).thenReturn(sessionManagerEnabled);
        leasePool = mock(ai.careerpilot.execution.browser.pool.BrowserLeasePool.class);
        return new PlaywrightAutomationProvider(sessionManager, leasePool, metrics, flagEnabled);
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
}
