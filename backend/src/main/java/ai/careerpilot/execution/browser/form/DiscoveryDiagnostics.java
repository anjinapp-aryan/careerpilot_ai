package ai.careerpilot.execution.browser.form;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase B — what discovery actually did, as opposed to what it returned.
 *
 * <p>Before this record existed, a reduced field list and a genuinely sparse page were
 * indistinguishable in the report, and there was no way to tell a form that discovery
 * <em>mishandled</em> from a form that is simply short. Every counter here answers a question an
 * operator would otherwise have to reproduce in a browser by hand.
 *
 * <p><b>This is deliberately separate from {@code AutomationConfidence}.</b> That score answers
 * "can we fill this form"; {@link #labelQuality()} answers "did we understand the page". Merging
 * them would let a discovery improvement inflate the readiness number, which is exactly the kind of
 * scoring drift Phase 12C.5 was built to prevent — the acceptance criterion for this phase is that
 * confidence improves because discovery got more accurate, not because the maths changed.
 *
 * @param rawCandidates      controls the script saw before any reduction
 * @param discoveredControls controls surviving reduction — the list handed downstream
 * @param duplicatesRemoved  exact selector collisions dropped (shadow/frame traversal can re-emit)
 * @param phantomsRemoved    framework helper controls collapsed onto their real sibling
 * @param hiddenControls     discovered controls not visible to a user (retained, never filled)
 * @param frameControls      controls found inside same-origin iframes
 * @param shadowControls     controls found inside open shadow roots
 * @param unlabelledControls controls discovery could not attach any human-readable label to
 * @param rootsTraversed     documents and shadow roots walked
 * @param shadowRootsFound   open shadow roots encountered
 * @param sameOriginFrames   iframes whose document was reachable
 * @param crossOriginFrames  iframes the browser refused access to — reported, never worked around
 * @param durationMs         wall-clock discovery time
 * @param notes              human-readable observations, each one actionable
 */
public record DiscoveryDiagnostics(
        int rawCandidates,
        int discoveredControls,
        int duplicatesRemoved,
        int phantomsRemoved,
        int hiddenControls,
        int frameControls,
        int shadowControls,
        int unlabelledControls,
        int rootsTraversed,
        int shadowRootsFound,
        int sameOriginFrames,
        int crossOriginFrames,
        long durationMs,
        List<String> notes) {

    public DiscoveryDiagnostics {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /** The traversal counters only the browser can report. */
    public record RawCounters(int rawCandidates, int rootsTraversed, int shadowRootsFound,
                              int sameOriginFrames, int crossOriginFrames) {

        /**
         * A pre-Phase-B script result, or a malformed one. Zeroes rather than a throw — a missing
         * diagnostic must never cost the caller its field list.
         */
        public static RawCounters unknown() {
            return new RawCounters(0, 0, 0, 0, 0);
        }
    }

    public static DiscoveryDiagnostics empty() {
        return new DiscoveryDiagnostics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0L, List.of());
    }

    /**
     * The share of discovered controls carrying a real, non-placeholder label, as a percentage.
     *
     * <p>This is a <em>discovery</em> quality measure, never an automation-readiness one. A control
     * with no label cannot be classified, so this is the ceiling on how much of a page can ever be
     * understood — which makes it the number to watch when a new ATS is onboarded.
     *
     * <p>Returns 100 when nothing was discovered: reporting 0% for a page with no form would read
     * as a discovery failure rather than an empty page, and the two are different findings.
     */
    public int labelQuality() {
        if (discoveredControls <= 0) return 100;
        int labelled = discoveredControls - unlabelledControls;
        return (int) Math.round(100.0 * Math.max(0, labelled) / discoveredControls);
    }

    public DiscoveryDiagnostics withDuration(long millis) {
        return new DiscoveryDiagnostics(rawCandidates, discoveredControls, duplicatesRemoved,
                phantomsRemoved, hiddenControls, frameControls, shadowControls, unlabelledControls,
                rootsTraversed, shadowRootsFound, sameOriginFrames, crossOriginFrames,
                Math.max(0L, millis), notes);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rawCandidates", rawCandidates);
        out.put("discoveredControls", discoveredControls);
        out.put("duplicatesRemoved", duplicatesRemoved);
        out.put("phantomsRemoved", phantomsRemoved);
        out.put("hiddenControls", hiddenControls);
        out.put("frameControls", frameControls);
        out.put("shadowControls", shadowControls);
        out.put("unlabelledControls", unlabelledControls);
        out.put("rootsTraversed", rootsTraversed);
        out.put("shadowRootsFound", shadowRootsFound);
        out.put("sameOriginFrames", sameOriginFrames);
        out.put("crossOriginFrames", crossOriginFrames);
        out.put("labelQuality", labelQuality());
        out.put("durationMs", durationMs);
        out.put("notes", notes);
        return out;
    }
}
