package ai.careerpilot.execution.browser.pool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enterprise Browser Automation — the launch flags are the difference between Chromium starting on
 * the Oracle Cloud VM and throwing at launch. Each assertion below maps to a specific, audited
 * deployment blocker, so a well-meaning cleanup that drops one fails loudly here.
 */
class BrowserLaunchOptionsFactoryTest {

    private BrowserLaunchOptionsFactory factory(boolean noSandbox, boolean disableDevShm,
                                                 String executablePath, String channel) {
        return new BrowserLaunchOptionsFactory(noSandbox, disableDevShm, 256, executablePath, channel, 60000);
    }

    @SuppressWarnings("unchecked")
    private List<String> argsOf(BrowserLaunchOptionsFactory factory) {
        return (List<String>) factory.describe().get("args");
    }

    @Test
    void disableDevShmIsPresentByDefault_dockerDefaultShmIs64Mb() {
        assertThat(argsOf(factory(true, true, "", ""))).contains("--disable-dev-shm-usage");
    }

    @Test
    void noSandboxIsPresentByDefault_containerHasNoUserNamespace() {
        List<String> args = argsOf(factory(true, true, "", ""));
        assertThat(args).contains("--no-sandbox", "--disable-setuid-sandbox");
    }

    @Test
    void sandboxFlagsCanBeDisabledForAHardenedRuntime() {
        List<String> args = argsOf(factory(false, true, "", ""));
        assertThat(args).doesNotContain("--no-sandbox", "--disable-setuid-sandbox");
    }

    @Test
    void rendererHeapIsCapped_uncappedRendererOnA3GbHostIsAnOomRisk() {
        assertThat(argsOf(factory(true, true, "", ""))).contains("--js-flags=--max-old-space-size=256");
    }

    @Test
    void singleProcessIsNeverUsed_oneRendererCrashMustNotKillEveryConcurrentJob() {
        assertThat(argsOf(factory(true, true, "", ""))).doesNotContain("--single-process");
    }

    @Test
    void memoryWastefulSubsystemsAreDisabled() {
        List<String> args = argsOf(factory(true, true, "", ""));
        assertThat(args).contains("--disable-gpu", "--disable-extensions", "--disable-background-networking");
        assertThat(args).anyMatch(a -> a.startsWith("--disable-features=") && a.contains("BackForwardCache"));
    }

    @Test
    void describeReportsBundledBrowserWhenNoArmOverrideIsConfigured() {
        Map<String, Object> described = factory(true, true, "", "").describe();
        assertThat(described.get("executablePath")).isEqualTo("(playwright bundled)");
        assertThat(described.get("channel")).isEqualTo("(none)");
    }

    @Test
    void describeReportsExplicitExecutablePath_theArmDeploymentRoute() {
        Map<String, Object> described = factory(true, true, "/usr/bin/chromium", "").describe();
        assertThat(described.get("executablePath")).isEqualTo("/usr/bin/chromium");
    }

    @Test
    void describeReportsChannelWhenConfigured() {
        assertThat(factory(true, true, "", "chromium").describe().get("channel")).isEqualTo("chromium");
    }

    @Test
    void blankAndNullOverridesAreTreatedAsUnset() {
        BrowserLaunchOptionsFactory nulls =
                new BrowserLaunchOptionsFactory(true, true, 256, null, null, 60000);
        assertThat(nulls.describe().get("executablePath")).isEqualTo("(playwright bundled)");
        assertThat(factory(true, true, "   ", "  ").describe().get("channel")).isEqualTo("(none)");
    }
}
