package ai.careerpilot.execution.browser.rollout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Phase 12B — the staged-rollout gate for real browser automation.
 *
 * <p><b>This is a second, independent gate — it never replaces {@code browser.automation.enabled}.</b>
 * That master flag still decides whether a Chromium process may exist at all
 * ({@code BrowserSessionManager}); this class decides, per user, whether an execution that is
 * otherwise fully eligible may actually drive it. Both must say yes. The practical consequence is
 * the one this phase exists to provide: enabling the feature no longer means enabling it for
 * everyone at once, and reverting does not require a redeploy.
 *
 * <p><b>Default is 0%, which is byte-identical to today.</b> Turning {@code browser.automation.enabled}
 * on without also raising the percentage or naming an allow-listed user changes nothing about which
 * executions run — they still take the pre-existing "not enabled in this build" ABORTED path. That
 * is deliberate: the risky flag and the traffic-exposing flag are separated so Stage 0 (infrastructure
 * running, zero real automation) is a genuine, observable state rather than a promise.
 *
 * <h2>Bucketing</h2>
 * A user's bucket is derived from a stable hash of their {@link UUID}, so a given user is either in
 * or out for the whole rollout and does not oscillate between requests. Two properties matter and
 * are both tested:
 * <ul>
 *   <li><b>Deterministic</b> — same user, same answer, forever (until the percentage changes).</li>
 *   <li><b>Monotonic</b> — anyone included at 5% is still included at 25%. Raising the percentage
 *       only ever adds users; it never swaps one cohort for another. Without this, "go from 5% to
 *       25%" would silently drop the very users whose successful runs justified the increase.</li>
 * </ul>
 * {@code Math.floorMod} guards the sign, since {@code UUID#hashCode} is freely negative and
 * {@code % 100} on a negative int yields a negative bucket that would never match.
 *
 * <h2>Allow-list</h2>
 * The allow-list is checked <em>before</em> the percentage and is what makes Stage 1 (internal test
 * users only) expressible without exposing any real user: percentage stays 0, and only the named
 * ids get through.
 *
 * <p>Never throws. A malformed uuid in the allow-list is dropped at construction with a warning
 * rather than failing startup — a typo in an ops config must not take the application down.
 */
@Component
public class BrowserRolloutGate {

    private static final Logger log = LoggerFactory.getLogger(BrowserRolloutGate.class);

    private final int percentage;
    private final Set<UUID> allowedUserIds;
    private final String stageLabel;

    private final AtomicLong admitted = new AtomicLong();
    private final AtomicLong deniedByPercentage = new AtomicLong();
    private final AtomicLong deniedMissingUser = new AtomicLong();

    public BrowserRolloutGate(
            @Value("${browser.automation.rollout.percentage:0}") int percentage,
            @Value("${browser.automation.rollout.allowed-user-ids:}") String allowedUserIdsCsv,
            @Value("${browser.automation.rollout.stage-label:STAGE_0_OFF}") String stageLabel) {
        this.percentage = Math.max(0, Math.min(100, percentage));
        this.allowedUserIds = parseUuids(allowedUserIdsCsv);
        this.stageLabel = stageLabel == null || stageLabel.isBlank() ? "UNLABELLED" : stageLabel.trim();
        log.info("BROWSER_ROLLOUT initialised stage={} percentage={}% allowListSize={}",
                this.stageLabel, this.percentage, this.allowedUserIds.size());
    }

    /**
     * Whether this user may drive real browser automation.
     *
     * <p>A null user id returns {@code false}. There is no "unknown user" fallback to the percentage
     * bucket, because an unattributable execution is exactly the one that must not silently reach a
     * live employer form — and a null id cannot be bucketed deterministically anyway.
     */
    public boolean isEnabledFor(UUID userId) {
        if (userId == null) {
            deniedMissingUser.incrementAndGet();
            return false;
        }
        if (allowedUserIds.contains(userId)) {
            admitted.incrementAndGet();
            return true;
        }
        if (percentage <= 0) {
            deniedByPercentage.incrementAndGet();
            return false;
        }
        if (bucketOf(userId) < percentage) {
            admitted.incrementAndGet();
            return true;
        }
        deniedByPercentage.incrementAndGet();
        return false;
    }

    /**
     * This user's stable bucket in {@code [0,100)}. Package-visible so the monotonicity property
     * above can be asserted directly rather than inferred from {@link #isEnabledFor} at several
     * percentages.
     */
    static int bucketOf(UUID userId) {
        // The UUID's own bits, not toString().hashCode(): stable across JVMs and independent of any
        // future change to UUID's string formatting.
        long mixed = userId.getMostSignificantBits() ^ userId.getLeastSignificantBits();
        // Fold to an int, then take an absolute-safe modulus.
        int folded = (int) (mixed ^ (mixed >>> 32));
        return Math.floorMod(folded, 100);
    }

    public int percentage() {
        return percentage;
    }

    public boolean isFullyOff() {
        return percentage <= 0 && allowedUserIds.isEmpty();
    }

    /** Diagnostics only. Emits the allow-list <em>size</em>, never the ids — those are user PII. */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stage", stageLabel);
        out.put("percentage", percentage);
        out.put("allowListSize", allowedUserIds.size());
        out.put("fullyOff", isFullyOff());
        out.put("admitted", admitted.get());
        out.put("deniedByPercentage", deniedByPercentage.get());
        out.put("deniedMissingUser", deniedMissingUser.get());
        return out;
    }

    private static Set<UUID> parseUuids(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(BrowserRolloutGate::parseOrNull)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static UUID parseOrNull(String raw) {
        try {
            return UUID.fromString(raw.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // A typo in an ops-managed allow-list must not prevent the application from starting.
            log.warn("BROWSER_ROLLOUT ignoring malformed allow-list user id '{}'",
                    raw.length() > 40 ? raw.substring(0, 40) + "…" : raw);
            return null;
        }
    }
}
