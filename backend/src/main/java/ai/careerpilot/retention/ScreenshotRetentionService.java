package ai.careerpilot.retention;

import ai.careerpilot.storage.S3StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P2 Work Item 3 — age out browser screenshots so object storage does not grow without bound.
 *
 * <h2>Why this is needed</h2>
 * Measured during the F5 audit: 105 validation screenshots occupied 90 MB (≈878 KB each, full-page
 * PNGs). At 100 validations a day that is roughly 30 GB a year, on a single small VM whose object
 * store shares the same disk as everything else, with nothing anywhere that ever deleted one.
 *
 * <h2>The rule that shapes this class</h2>
 * <b>Only diagnostic evidence is ever deleted.</b> There are two screenshot prefixes and they are
 * not interchangeable:
 *
 * <ul>
 *   <li>{@code browser-validation/…} — produced by the validation harness. Nobody's application
 *       depends on it; it exists so an operator can see what a page looked like. <b>Deletable.</b></li>
 *   <li>{@code execution-screenshots/…} — the image a human reviews at the mandatory
 *       {@code FORM_SCREENSHOT} approval gate, and the corroborating evidence behind a submission's
 *       verification verdict. Deleting one could destroy the record of what a candidate actually
 *       approved being sent to an employer. <b>Never touched by this class</b>, and that is
 *       structural: {@link #VALIDATION_PREFIX} is the only prefix it can name, and
 *       {@link #isDeletable} refuses anything else.</li>
 * </ul>
 *
 * <p>Retention is deliberately generous by default (30 days). The failure mode of keeping a
 * diagnostic image too long is a little disk; the failure mode of deleting one too early is an
 * operator investigating a selector regression with no picture of the page.
 *
 * <h2>Retry safety</h2>
 * Deletion is idempotent (S3 succeeds on an absent key) and each object is deleted independently
 * inside its own try/catch, so a sweep interrupted halfway — or one that hits a transient storage
 * error on object 40 of 200 — simply resumes on the next run. Nothing is batched into an
 * all-or-nothing operation.
 *
 * <p>Gated by {@code retention.screenshots.enabled} (default {@code false}), this codebase's
 * dark-by-default convention. Deliberately separate from {@link RetentionService}, which purges
 * database rows in transactions — object storage has no transaction to join, and mixing the two
 * would mean a storage error could roll back a row purge that had already succeeded.
 */
@Service
public class ScreenshotRetentionService {

    private static final Logger log = LoggerFactory.getLogger(ScreenshotRetentionService.class);

    /** The only prefix this service is permitted to delete from. */
    public static final String VALIDATION_PREFIX = "browser-validation/";

    /**
     * Human-approval and verification evidence. Named here solely so the exclusion is greppable and
     * a future edit that tries to add it fails a test rather than passing review.
     */
    public static final String PROTECTED_EXECUTION_PREFIX = "execution-screenshots/";

    private final S3StorageService storage;
    private final boolean enabled;
    private final Duration retention;
    private final int maxPerSweep;

    private final AtomicLong sweeps = new AtomicLong();
    private final AtomicLong deleted = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private volatile Instant lastSweepAt;
    private volatile String lastError;

    public ScreenshotRetentionService(
            S3StorageService storage,
            @Value("${retention.screenshots.enabled:false}") boolean enabled,
            @Value("${retention.screenshots.validation-days:30}") int validationDays,
            @Value("${retention.screenshots.max-per-sweep:200}") int maxPerSweep) {
        this.storage = storage;
        this.enabled = enabled;
        // A zero or negative window would mean "delete everything immediately", which is never what
        // a misconfiguration should achieve. Clamp to at least one day.
        this.retention = Duration.ofDays(Math.max(1, validationDays));
        this.maxPerSweep = Math.max(1, maxPerSweep);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * True only for a key that is diagnostic evidence. Pure and public so the guarantee is directly
     * testable without any storage involved.
     */
    public static boolean isDeletable(String key) {
        if (key == null || key.isBlank()) return false;
        if (key.startsWith(PROTECTED_EXECUTION_PREFIX)) return false;
        return key.startsWith(VALIDATION_PREFIX);
    }

    /** Hourly. Frequent enough to keep the backlog small, cheap enough to be invisible on 1 vCPU. */
    @Scheduled(fixedDelayString = "${retention.screenshots.interval-ms:3600000}",
               initialDelayString = "${retention.screenshots.interval-ms:3600000}")
    public void scheduledSweep() {
        if (!enabled) return;
        try {
            sweep();
        } catch (Exception e) {
            // A retention sweep must never take the scheduler thread down with it.
            failures.incrementAndGet();
            lastError = e.toString();
            log.warn("SCREENSHOT_RETENTION sweep failed: {}", e.toString());
        }
    }

    /**
     * Delete diagnostic screenshots older than the retention window.
     *
     * @return how many objects were deleted
     */
    public int sweep() {
        if (!enabled) return 0;
        sweeps.incrementAndGet();
        lastSweepAt = Instant.now();
        Instant cutoff = Instant.now().minus(retention);

        List<String> candidates;
        try {
            candidates = storage.listKeysOlderThan(VALIDATION_PREFIX, cutoff, maxPerSweep);
        } catch (Exception e) {
            failures.incrementAndGet();
            lastError = e.toString();
            log.warn("SCREENSHOT_RETENTION list failed: {}", e.toString());
            return 0;
        }

        int removed = 0;
        for (String key : candidates) {
            // Defence in depth. listKeysOlderThan was already scoped to the validation prefix, but a
            // deletion loop is exactly the place to re-check rather than trust its input.
            if (!isDeletable(key)) {
                log.warn("SCREENSHOT_RETENTION refusing to delete protected key {}", key);
                continue;
            }
            try {
                storage.delete(key);
                removed++;
            } catch (Exception e) {
                // Per-object isolation: one unreachable object must not strand the other 199, and
                // the next sweep retries it because deletion is idempotent.
                failures.incrementAndGet();
                lastError = e.toString();
                log.warn("SCREENSHOT_RETENTION delete failed key={}: {}", key, e.toString());
            }
        }
        deleted.addAndGet(removed);
        if (removed > 0) {
            log.info("SCREENSHOT_RETENTION deleted {} validation screenshot(s) older than {} day(s)",
                    removed, retention.toDays());
        }
        return removed;
    }

    /** Diagnostics. Counts only — never a key, since keys embed ids. */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("validationRetentionDays", retention.toDays());
        out.put("maxPerSweep", maxPerSweep);
        out.put("sweeps", sweeps.get());
        out.put("objectsDeleted", deleted.get());
        out.put("failures", failures.get());
        out.put("lastSweepAt", lastSweepAt == null ? null : lastSweepAt.toString());
        out.put("lastError", lastError);
        out.put("protectedPrefix", PROTECTED_EXECUTION_PREFIX);
        out.put("policy", "diagnostic validation screenshots only — human-approval and verification "
                + "evidence under " + PROTECTED_EXECUTION_PREFIX + " is never deleted");
        return out;
    }
}
