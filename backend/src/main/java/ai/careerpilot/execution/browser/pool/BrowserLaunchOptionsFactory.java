package ai.careerpilot.execution.browser.pool;

import com.microsoft.playwright.BrowserType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Enterprise Browser Automation — the single place Chromium launch configuration is decided.
 *
 * <p>Before this class, {@code BrowserSessionManager} launched with {@code setHeadless(true)} and
 * <b>nothing else</b>, which cannot work on this platform's actual deployment target for three
 * independent reasons, all confirmed by audit:
 * <ul>
 *   <li><b>{@code /dev/shm} is 64 MB</b> in Docker by default and no {@code shm_size} is set in
 *       {@code docker-compose.yml}. Chromium maps its renderer shared memory there and tabs crash.
 *       {@code --disable-dev-shm-usage} moves that to {@code /tmp} instead.</li>
 *   <li><b>The container has no user namespace</b>, so Chromium's setuid sandbox cannot initialise
 *       and the browser refuses to start. See {@code browser.automation.launch.no-sandbox} below —
 *       this is a real security trade-off, made explicit rather than hidden.</li>
 *   <li><b>ARM</b>: Playwright publishes no Chromium build for {@code linux-arm64}. The supported
 *       route is the distribution's own Chromium via {@code executable-path} / {@code channel},
 *       which nothing previously set.</li>
 * </ul>
 *
 * <p>Every flag is memory- or stability-motivated and documented inline. Deliberately <b>NOT</b>
 * included: {@code --single-process}. It saves roughly 40–60 MB but makes any renderer crash take
 * the whole browser down, which on a single-browser deployment means every concurrent job dies.
 */
@Component
public class BrowserLaunchOptionsFactory {

    private static final Logger log = LoggerFactory.getLogger(BrowserLaunchOptionsFactory.class);

    private final boolean noSandbox;
    private final boolean disableDevShm;
    private final int maxOldSpaceMb;
    private final String executablePath;
    private final String channel;
    private final int launchTimeoutMs;

    public BrowserLaunchOptionsFactory(
            @Value("${browser.automation.launch.no-sandbox:true}") boolean noSandbox,
            @Value("${browser.automation.launch.disable-dev-shm:true}") boolean disableDevShm,
            @Value("${browser.automation.launch.max-old-space-mb:256}") int maxOldSpaceMb,
            @Value("${browser.automation.launch.executable-path:}") String executablePath,
            @Value("${browser.automation.launch.channel:}") String channel,
            @Value("${browser.automation.launch.timeout-ms:60000}") int launchTimeoutMs) {
        this.noSandbox = noSandbox;
        this.disableDevShm = disableDevShm;
        this.maxOldSpaceMb = maxOldSpaceMb;
        this.executablePath = executablePath == null ? "" : executablePath.trim();
        this.channel = channel == null ? "" : channel.trim();
        this.launchTimeoutMs = launchTimeoutMs;
    }

    public BrowserType.LaunchOptions create() {
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setTimeout(launchTimeoutMs)
                .setArgs(args());

        // ARM / distro-Chromium support. Playwright ships no linux-arm64 Chromium, so on ARM one of
        // these MUST be set or launch fails outright. Both empty preserves the previous behaviour
        // (use Playwright's own bundled browser) so x86 dev machines are unaffected.
        if (!executablePath.isEmpty()) {
            options.setExecutablePath(java.nio.file.Path.of(executablePath));
            log.info("BROWSER_LAUNCH using explicit chromium executable: {}", executablePath);
        } else if (!channel.isEmpty()) {
            options.setChannel(channel);
            log.info("BROWSER_LAUNCH using chromium channel: {}", channel);
        }
        return options;
    }

    private List<String> args() {
        List<String> args = new ArrayList<>();

        if (disableDevShm) {
            // Without this, Chromium tabs crash on Docker's default 64 MB /dev/shm.
            args.add("--disable-dev-shm-usage");
        }
        if (noSandbox) {
            // SECURITY TRADE-OFF, stated explicitly: this disables Chromium's process sandbox, so a
            // renderer exploit on a hostile job page is contained only by the container boundary
            // rather than by two layers. It is required when the container cannot provide a user
            // namespace. The safer alternative is to keep the sandbox and grant SYS_ADMIN / a
            // seccomp profile at the container level, then set this to false.
            args.add("--no-sandbox");
            args.add("--disable-setuid-sandbox");
        }

        // Memory: cap the V8 old space per renderer. A job application form never needs a large
        // heap, and an uncapped renderer on a 3 GB host is the difference between a slow job and an
        // OOM kill that takes the whole stack with it.
        args.add("--js-flags=--max-old-space-size=" + maxOldSpaceMb);

        // Strip subsystems that cost memory/CPU and are irrelevant to filling a form headlessly.
        args.add("--disable-gpu");
        args.add("--disable-extensions");
        args.add("--disable-background-networking");
        args.add("--disable-background-timer-throttling");
        args.add("--disable-renderer-backgrounding");
        args.add("--disable-backgrounding-occluded-windows");
        args.add("--disable-client-side-phishing-detection");
        args.add("--disable-sync");
        args.add("--disable-default-apps");
        args.add("--no-first-run");
        args.add("--no-default-browser-check");
        args.add("--mute-audio");
        args.add("--metrics-recording-only");
        // BackForwardCache retains whole page snapshots in memory — pure cost for a single-shot flow.
        args.add("--disable-features=Translate,BackForwardCache,AcceptCHFrame,MediaRouter,OptimizationHints");

        return args;
    }

    /**
     * True when an external browser binary is configured, i.e. Playwright is driving a browser it
     * did not install and therefore must not try to download one.
     */
    public boolean usesExternalBrowser() {
        return !executablePath.isEmpty() || !channel.isEmpty();
    }

    /**
     * Environment for the Playwright driver process.
     *
     * <p><b>This exists because of a measured, repeating waste.</b> Playwright's Java driver runs
     * its {@code install} step on the first {@code Playwright.create()} of every process. With an
     * explicit {@code executable-path} configured — which is mandatory on ARM, where Playwright
     * publishes no {@code linux-arm64} Chromium at all — that step downloaded Chromium, FFMPEG,
     * <em>Firefox</em> and <em>WebKit</em> into the container's cache and then the launch used
     * {@code /usr/bin/chromium} regardless. Measured on a cold container: 83,432 ms of the first
     * request's latency, hundreds of megabytes of disk in a layer with no volume behind it (so it
     * repeated on every restart), and a hard dependency on outbound access to
     * {@code playwright.azureedge.net} — one download attempt in the captured run timed out after
     * 30s before falling back to a mirror.
     *
     * <p>{@code PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD} is only set when an external browser is actually
     * configured. A developer running with no {@code executable-path} still gets Playwright's own
     * bundled browsers downloaded as normal, because in that case they are the browser.
     */
    public java.util.Map<String, String> driverEnv() {
        if (!usesExternalBrowser()) return java.util.Map.of();
        return java.util.Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
    }

    /** Diagnostics only — never logged with credentials, there are none here. */
    public java.util.Map<String, Object> describe() {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("noSandbox", noSandbox);
        out.put("disableDevShm", disableDevShm);
        out.put("maxOldSpaceMb", maxOldSpaceMb);
        out.put("executablePath", executablePath.isEmpty() ? "(playwright bundled)" : executablePath);
        out.put("channel", channel.isEmpty() ? "(none)" : channel);
        out.put("launchTimeoutMs", launchTimeoutMs);
        out.put("skipBrowserDownload", usesExternalBrowser());
        out.put("args", args());
        return out;
    }
}
