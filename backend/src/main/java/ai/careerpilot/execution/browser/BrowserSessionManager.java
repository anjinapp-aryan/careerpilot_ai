package ai.careerpilot.execution.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Gap D — owns the process-wide Playwright/Chromium lifecycle. One shared {@link Playwright} and
 * headless {@link Browser} instance at the Spring bean level (expensive to start; safe to share),
 * lazily launched on first use so a build with {@code browser.automation.enabled=false} never
 * starts a browser process at all. Every caller gets its own isolated {@link BrowserContext} via
 * {@link #newContext()} — contexts are cheap, hold no shared cookies/storage, and MUST be closed by
 * the caller after use (never reused across users/executions; see
 * {@link PlaywrightAutomationProvider}).
 */
@Component
public class BrowserSessionManager {

    private static final Logger log = LoggerFactory.getLogger(BrowserSessionManager.class);

    private final boolean enabled;

    private volatile Playwright playwright;
    private volatile Browser browser;
    private final Object lock = new Object();

    public BrowserSessionManager(@Value("${browser.automation.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Lazily launches headless Chromium on first call. Throws if the flag is off. */
    private Browser browser() {
        if (!enabled) {
            throw new IllegalStateException("browser automation disabled (browser.automation.enabled=false)");
        }
        Browser b = browser;
        if (b == null) {
            synchronized (lock) {
                b = browser;
                if (b == null) {
                    playwright = Playwright.create();
                    browser = b = playwright.chromium().launch(
                            new BrowserType.LaunchOptions().setHeadless(true));
                    log.info("BROWSER_SESSION_MANAGER launched headless chromium");
                }
            }
        }
        return b;
    }

    /** A brand-new, isolated browser context — no shared cookies/storage with any other execution. */
    public BrowserContext newContext() {
        return browser().newContext();
    }

    @PreDestroy
    public void shutdown() {
        synchronized (lock) {
            try {
                if (browser != null) browser.close();
            } catch (Exception e) {
                log.warn("BROWSER_SESSION_MANAGER browser close failed: {}", e.toString());
            }
            try {
                if (playwright != null) playwright.close();
            } catch (Exception e) {
                log.warn("BROWSER_SESSION_MANAGER playwright close failed: {}", e.toString());
            }
            browser = null;
            playwright = null;
        }
    }
}
