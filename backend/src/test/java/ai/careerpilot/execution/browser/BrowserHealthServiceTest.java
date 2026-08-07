package ai.careerpilot.execution.browser;

import ai.careerpilot.execution.browser.pool.BrowserLaunchOptionsFactory;
import ai.careerpilot.execution.browser.pool.BrowserLeasePool;
import ai.careerpilot.execution.browser.pool.BrowserPoolMetrics;
import ai.careerpilot.execution.browser.rollout.BrowserRolloutGate;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 12B — browser health reporting. The point of these tests is that the report tells the truth
 * about states the operator cannot otherwise see: a missing browser binary, an ARM box with no
 * distro Chromium configured, and a launch that has already failed.
 */
class BrowserHealthServiceTest {

    private final BrowserSessionManager sessionManager = mock(BrowserSessionManager.class);
    private final BrowserLeasePool leasePool = mock(BrowserLeasePool.class);
    private final BrowserPoolMetrics poolMetrics = new BrowserPoolMetrics();
    private final BrowserLifecycleMetrics lifecycleMetrics = new BrowserLifecycleMetrics();
    private final BrowserAutomationMetrics automationMetrics = new BrowserAutomationMetrics();

    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<
            ai.careerpilot.retention.ScreenshotRetentionService> absentRetention() {
        var provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private BrowserHealthService service(boolean enabled, String executablePath, String channel) {
        return new BrowserHealthService(sessionManager, leasePool, poolMetrics, lifecycleMetrics,
                automationMetrics,
                new BrowserLaunchOptionsFactory(true, true, 256, executablePath, channel, 60000),
                new BrowserRolloutGate(0, "", "STAGE_0_OFF"),
                new ai.careerpilot.execution.browser.form.FormAutomationMetrics(),
                new ai.careerpilot.execution.browser.validation.BrowserValidationMetrics(),
                new ai.careerpilot.execution.browser.validation.ValidationUrlPolicy(true, "", true),
                // Phase 13A — history off, so this suite keeps measuring exactly what it did before.
                new ai.careerpilot.execution.browser.validation.ValidationHistoryService(
                        mock(ai.careerpilot.repo.AtsValidationRunRepository.class),
                        new ai.careerpilot.execution.browser.validation.SelectorDriftDetector(10, 2), false),
                // P2 WI3 — retention is optional and has its own flag; absent here, so this suite
                // keeps measuring exactly what it did before.
                absentRetention(),
                enabled, executablePath);
    }

    @Test
    void disabledReportsNotConfiguredAndNeverTouchesTheBrowser() {
        Map<String, Object> report = service(false, "", "").report();
        assertThat(report).containsEntry("enabled", false).containsEntry("health", "NOT_CONFIGURED");
        // Critically: reading health must not be the thing that starts Chromium.
        org.mockito.Mockito.verify(sessionManager, org.mockito.Mockito.never()).newContext();
    }

    @Test
    void aConfiguredButMissingExecutableIsReportedDownRatherThanUp() {
        Map<String, Object> report = service(true, "/definitely/not/a/real/chromium", "").report();

        @SuppressWarnings("unchecked")
        Map<String, Object> installation = (Map<String, Object>) report.get("installation");
        assertThat(installation).containsEntry("browserInstalled", false);
        assertThat(String.valueOf(installation.get("browserInstalledNote"))).contains("launch will fail");
        assertThat(report).containsEntry("health", "DOWN");
    }

    @Test
    void anUnconfiguredPathReportsUnknownRatherThanClaimingTheBrowserIsMissing() {
        Map<String, Object> report = service(true, "", "").report();
        @SuppressWarnings("unchecked")
        Map<String, Object> installation = (Map<String, Object>) report.get("installation");
        // null, not false: Playwright's bundled-browser location is internal and unverifiable here.
        assertThat(installation.get("browserInstalled")).isNull();
        assertThat(installation).containsKey("browserInstalledNote");
    }

    @Test
    void chromiumVersionIsOnlyClaimedWhenTheBrowserIsActuallyLaunched() {
        when(sessionManager.isLaunched()).thenReturn(false);
        @SuppressWarnings("unchecked")
        Map<String, Object> session = (Map<String, Object>) service(true, "", "").report().get("session");
        assertThat(session.get("chromiumVersion")).isNull();
        assertThat(session).containsEntry("launched", false);
    }

    @Test
    void armCompatibilityDependsOnAnExplicitBrowserBeingConfigured() {
        Map<String, Object> report = service(true, "", "chrome").report();
        @SuppressWarnings("unchecked")
        Map<String, Object> runtime = (Map<String, Object>) report.get("runtime");
        // With a channel configured, ARM is satisfied regardless of the host this test runs on.
        assertThat(runtime).containsEntry("armCompatible", true);
        assertThat(runtime.get("armCompatibilityNote")).isNull();
    }

    @Test
    void aFailedLaunchWithNoLiveBrowserIsDown() {
        lifecycleMetrics.recordLaunchAttempt();
        lifecycleMetrics.recordLaunchFailure("Executable doesn't exist at /usr/bin/chromium");
        when(sessionManager.isLaunched()).thenReturn(false);

        Map<String, Object> report = service(true, "", "chrome").report();
        assertThat(report).containsEntry("health", "DOWN");
        @SuppressWarnings("unchecked")
        Map<String, Object> lifecycle = (Map<String, Object>) report.get("lifecycle");
        assertThat(String.valueOf(lifecycle.get("lastLaunchError"))).contains("Executable doesn't exist");
        assertThat(lifecycle).containsEntry("launchFailures", 1L);
    }

    @Test
    void anIntermittentLaunchFailureWithALiveBrowserIsDegradedNotDown() {
        lifecycleMetrics.recordLaunchAttempt();
        lifecycleMetrics.recordLaunchFailure("transient");
        lifecycleMetrics.recordLaunchAttempt();
        lifecycleMetrics.recordLaunchSuccess(1200);
        when(sessionManager.isLaunched()).thenReturn(true);
        when(sessionManager.browserVersion()).thenReturn("151.0.7922.71");

        Map<String, Object> report = service(true, "", "chrome").report();
        assertThat(report).containsEntry("health", "DEGRADED");
    }

    @Test
    void leaseExhaustionDegradesHealth() {
        when(sessionManager.isLaunched()).thenReturn(true);
        poolMetrics.recordAcquireTimeout();
        assertThat(service(true, "", "chrome").report()).containsEntry("health", "DEGRADED");
    }

    @Test
    void openPagesMirrorsActiveLeasesAndSaysSo() {
        when(leasePool.activeLeases()).thenReturn(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> session = (Map<String, Object>) service(true, "", "chrome").report().get("session");
        assertThat(session).containsEntry("openPages", 1);
        assertThat(String.valueOf(session.get("openPagesNote"))).contains("one page per lease");
    }

    @Test
    void aThrowingSourceDegradesOnlyItsOwnSectionNotTheWholeReport() {
        when(leasePool.snapshot()).thenThrow(new IllegalStateException("pool exploded"));
        Map<String, Object> report = service(true, "", "chrome").report();
        @SuppressWarnings("unchecked")
        Map<String, Object> capacity = (Map<String, Object>) report.get("capacity");
        assertThat(capacity).containsEntry("unavailable", true);
        // Everything else still present — a diagnostics endpoint that can fail is not a diagnostic.
        assertThat(report).containsKeys("runtime", "installation", "session", "lifecycle", "health");
    }

    @Test
    void memoryIsReportedAndIsHonestAboutExcludingChromium() {
        @SuppressWarnings("unchecked")
        Map<String, Object> runtime = (Map<String, Object>) service(true, "", "chrome").report().get("runtime");
        @SuppressWarnings("unchecked")
        Map<String, Object> memory = (Map<String, Object>) runtime.get("memory");
        assertThat(memory).containsKeys("jvmMaxMb", "jvmUsedMb", "availableProcessors");
        assertThat(String.valueOf(memory.get("note"))).contains("child process");
    }

    @Test
    void evidenceTimestampsSurfaceOnceCaptureHappens() {
        automationMetrics.recordScreenshotCaptured();
        automationMetrics.recordConfirmationCaptured();
        automationMetrics.recordSubmitLatency(4321);
        Map<String, Object> report = service(true, "", "chrome").report();
        assertThat(report.get("browserLastScreenshotAt")).isNotNull();
        assertThat(report.get("browserLastConfirmationAt")).isNotNull();
        assertThat(report).containsEntry("browserAvgSubmitDurationMs", 4321L);
    }

    @Test
    void rolloutStageIsVisibleInTheReport() {
        @SuppressWarnings("unchecked")
        Map<String, Object> rollout = (Map<String, Object>) service(true, "", "chrome").report().get("rollout");
        assertThat(rollout).containsEntry("stage", "STAGE_0_OFF")
                .containsEntry("percentage", 0)
                .containsEntry("fullyOff", true);
    }

    @Test
    void healthNeverThrowsEvenWhenEverySourceMisbehaves() {
        when(leasePool.snapshot()).thenThrow(new IllegalStateException("boom"));
        when(leasePool.isSaturated()).thenThrow(new IllegalStateException("boom"));
        when(sessionManager.isZombie()).thenThrow(new IllegalStateException("boom"));
        when(sessionManager.browserVersion()).thenThrow(new IllegalStateException("boom"));
        // Not asserting a particular verdict — only that assembling one is always possible.
        assertThat(service(true, "", "chrome").report()).containsKey("health");
    }

    @Test
    void lastCrashAndRestartAreSurfaced() {
        lifecycleMetrics.recordCrash("renderer died");
        lifecycleMetrics.recordRestart();
        @SuppressWarnings("unchecked")
        Map<String, Object> lifecycle =
                (Map<String, Object>) service(true, "", "chrome").report().get("lifecycle");
        assertThat(lifecycle).containsEntry("browserCrashes", 1L).containsEntry("browserRestarts", 1L);
        assertThat(lifecycle.get("lastCrashAt")).isNotNull();
        assertThat(lifecycle.get("lastRestartAt")).isNotNull();
        assertThat(String.valueOf(lifecycle.get("lastCrashReason"))).contains("renderer died");
    }

    @Test
    void anIdleDeploymentReportsFullLaunchSuccessRateRatherThanZero() {
        // "Never tried" is not a failure — reporting 0% would make every dark deployment look broken.
        assertThat(lifecycleMetrics.launchSuccessRate()).isEqualTo(100.0);
        assertThat(lifecycleMetrics.snapshot()).containsEntry("launchSuccessRate", 100.0);
    }

    @Test
    void launchErrorsAreTruncatedSoTheEndpointCannotLeakPageContent() {
        lifecycleMetrics.recordLaunchFailure("x".repeat(5000));
        String stored = String.valueOf(lifecycleMetrics.snapshot().get("lastLaunchError"));
        assertThat(stored.length()).isLessThan(500);
    }

    @Test
    void screenshotFailuresAreCountedSeparatelyFromCaptures() {
        automationMetrics.recordScreenshotCaptured();
        automationMetrics.recordScreenshotFailure();
        Map<String, Object> snapshot = automationMetrics.snapshot();
        assertThat(snapshot).containsEntry("browserScreenshotsCaptured", 1L)
                .containsEntry("browserScreenshotFailures", 1L);
    }

    @Test
    void anEmptyChannelStringIsNotMistakenForAConfiguredBrowser() {
        BrowserLaunchOptionsFactory factory = new BrowserLaunchOptionsFactory(true, true, 256, "", "  ", 60000);
        assertThat(factory.describe()).containsEntry("channel", "(none)");
    }
}
