package ai.careerpilot.execution.browser;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2E.2 — the Playwright provider is an INERT STUB. It must never be "configured" (even with
 * the flag on, since no real engine exists) and every action must throw, so there is no code path
 * that can drive a browser in this build.
 */
class PlaywrightAutomationProviderTest {

    private final PlaywrightAutomationProvider provider = new PlaywrightAutomationProvider(false);
    private final PlaywrightAutomationProvider flagged = new PlaywrightAutomationProvider(true);

    @Test
    void nameIsPlaywright() {
        assertThat(provider.name()).isEqualTo("playwright");
    }

    @Test
    void isNeverConfiguredEvenWithFlagOn() {
        assertThat(provider.isConfigured()).isFalse();
        assertThat(flagged.isConfigured()).isFalse();
    }

    @Test
    void flagEnabledReflectsTheFlagButNotReadiness() {
        assertThat(provider.flagEnabled()).isFalse();
        assertThat(flagged.flagEnabled()).isTrue();
        assertThat(flagged.isConfigured()).isFalse(); // flag on, still not configured
    }

    @Test
    void everyActionThrows() {
        assertThatThrownBy(() -> provider.login("url", Map.of())).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> provider.navigate("url")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> provider.uploadResume(Path.of("r.pdf"))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> provider.uploadCoverLetter(Path.of("c.pdf"))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> provider.fillForm(Map.of())).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> provider.answerQuestions(Map.of())).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(provider::submit).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> provider.captureScreenshot(Path.of("s.png"))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(provider::captureConfirmation).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(provider::logout).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void stubMessageMentionsNoPlaywrightDependency() {
        assertThatThrownBy(provider::submit)
                .hasMessageContaining("stub")
                .hasMessageContaining("no Playwright");
    }
}
