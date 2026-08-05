package ai.careerpilot.intelligence;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 13B — the provenance attached to every optimization finding. A finding without one of these
 * cannot be emitted; that is the mechanism behind "never recommend without evidence".
 *
 * <h2>Why confidence is a band and not a percentage</h2>
 * The phase brief's own examples show figures like "Confidence 96%". <b>This class deliberately does
 * not produce one.</b> A single number implies a calibrated probability that this platform cannot
 * compute: three applications and one interview is a 33% interview rate, and any percentage derived
 * from it would be arithmetic dressed up as certainty. Fabricating that number is precisely what
 * every prior phase's truthfulness rule forbids, and it would be the most persuasive fabrication in
 * the system — a user would act on it.
 *
 * <p>Instead: a {@link Level} derived solely from how much real evidence exists, published
 * alongside the raw counts so a reader can check the reasoning. "18 offers from 312 applications"
 * is a claim that can be verified. "96% confident" is not.
 *
 * @param source        which existing service or table this came from, named exactly
 * @param sampleSize    the denominator — how many observations back the finding
 * @param supporting    the numerator(s) that make the claim, e.g. interviews and offers
 * @param level         confidence band, from {@link #levelFor(int)}
 * @param lastUpdated   when the underlying data was last computed, or {@code null} if unknown
 */
public record Evidence(String source, int sampleSize, Map<String, Integer> supporting,
                       Level level, Instant lastUpdated) {

    /**
     * Bands are sample-size thresholds, stated openly rather than tuned. They answer "is there
     * enough here to act on", not "how likely is this to be true".
     */
    public enum Level {
        /** Below the floor — a finding at this level must not be surfaced as a recommendation. */
        INSUFFICIENT,
        LOW,
        MEDIUM,
        HIGH
    }

    /** Below this, a rate is noise and no recommendation may be made from it. */
    public static final int MINIMUM_SAMPLE = 5;
    private static final int LOW_CEILING = 20;
    private static final int MEDIUM_CEILING = 50;

    public Evidence {
        supporting = supporting == null ? Map.of() : Map.copyOf(supporting);
    }

    public static Level levelFor(int sampleSize) {
        if (sampleSize < MINIMUM_SAMPLE) return Level.INSUFFICIENT;
        if (sampleSize < LOW_CEILING) return Level.LOW;
        if (sampleSize < MEDIUM_CEILING) return Level.MEDIUM;
        return Level.HIGH;
    }

    public static Evidence of(String source, int sampleSize, Map<String, Integer> supporting, Instant lastUpdated) {
        return new Evidence(source, sampleSize, supporting, levelFor(sampleSize), lastUpdated);
    }

    /** No data at all. Distinct from a low-confidence finding: there is nothing here, not a little. */
    public static Evidence none(String source) {
        return new Evidence(source, 0, Map.of(), Level.INSUFFICIENT, null);
    }

    /** Whether this evidence is strong enough to support a surfaced recommendation. */
    public boolean isActionable() {
        return level != Level.INSUFFICIENT;
    }

    /**
     * A human-readable citation, e.g.
     * {@code "312 applications, 92 interviews, 18 offers (source: ResumeLearningService)"}.
     * This string is what appears in the Copilot's answer, so it must be checkable.
     */
    public String cite() {
        StringBuilder sb = new StringBuilder();
        sb.append(sampleSize).append(" observation").append(sampleSize == 1 ? "" : "s");
        supporting.forEach((k, v) -> sb.append(", ").append(v).append(' ').append(k));
        sb.append(" (source: ").append(source).append(')');
        return sb.toString();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", source);
        out.put("sampleSize", sampleSize);
        out.put("supporting", supporting);
        out.put("confidence", level.name());
        out.put("lastUpdated", lastUpdated == null ? null : lastUpdated.toString());
        out.put("citation", cite());
        return out;
    }
}
