package ai.careerpilot.intelligence;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 13B — one immutable view of what this user's own production evidence says works.
 *
 * <h2>What this deliberately does NOT contain</h2>
 * Mission state, career timeline, applications, interviews and company knowledge are <b>absent by
 * design</b>. {@code CareerContextService} (Phase 11A) already aggregates every one of them and is
 * already wired into the Copilot; restating them here would be the duplicate-aggregation this
 * phase's stop conditions forbid, and would create two sources that can disagree about the same
 * fact.
 *
 * <p>The division is deliberate and worth stating: {@code CareerContextService} answers
 * <em>"what is happening"</em>; this answers <em>"what the evidence says works"</em>. They compose
 * at the Copilot, not inside one another.
 *
 * <p>Every field is either backed by an {@link Evidence} citation or absent. There is no
 * partially-supported middle: a dimension with too few observations resolves to an empty list, not
 * to a low-confidence guess.
 */
public record ProductionOptimizationSnapshot(
        Instant generatedAt,
        ResumeIntelligence resume,
        List<DimensionFinding> countries,
        List<DimensionFinding> companies,
        List<DimensionFinding> skills,
        AtsIntelligence ats,
        List<String> notes) {

    public ProductionOptimizationSnapshot {
        countries = countries == null ? List.of() : List.copyOf(countries);
        companies = companies == null ? List.of() : List.copyOf(companies);
        skills = skills == null ? List.of() : List.copyOf(skills);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public static ProductionOptimizationSnapshot empty(String reason) {
        return new ProductionOptimizationSnapshot(Instant.now(), null, List.of(), List.of(),
                List.of(), null, List.of(reason));
    }

    /**
     * The best-performing resume version and the field of versions it beat.
     *
     * @param recommendedVersion the version with the highest offer rate, or {@code null} when no
     *                           version has enough evidence to name one
     */
    public record ResumeIntelligence(String recommendedVersion, int applications, int interviews,
                                     int offers, Double interviewRate, Double offerRate,
                                     int versionsCompared, Evidence evidence, String reason) {

        public Map<String, Object> snapshot() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("recommendedVersion", recommendedVersion);
            out.put("applications", applications);
            out.put("interviews", interviews);
            out.put("offers", offers);
            out.put("interviewRate", interviewRate);
            out.put("offerRate", offerRate);
            out.put("versionsCompared", versionsCompared);
            out.put("evidence", evidence == null ? null : evidence.snapshot());
            out.put("reason", reason);
            return out;
        }
    }

    /**
     * One key within a learned dimension (a country, a company, a skill) with its measured
     * performance. Shape is shared across dimensions because {@code SuccessPattern} already stores
     * them identically — inventing three near-identical records would be duplication for its own sake.
     */
    public record DimensionFinding(String dimension, String key, int applications, int interviews,
                                   int offers, Double successRate, Evidence evidence) {

        public Map<String, Object> snapshot() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("dimension", dimension);
            out.put("key", key);
            out.put("applications", applications);
            out.put("interviews", interviews);
            out.put("offers", offers);
            out.put("successRate", successRate);
            out.put("evidence", evidence == null ? null : evidence.snapshot());
            return out;
        }
    }

    /**
     * Automation readiness per ATS, from the Phase 13A validation history.
     *
     * <p>Note this is <em>automation</em> readiness — how reliably we can fill that ATS's forms —
     * and explicitly not "which ATS gets more interviews". The latter would need outcome data keyed
     * by ATS, which no table in this platform records; claiming it would be fabrication.
     */
    public record AtsIntelligence(String bestPlatform, Integer bestConfidence,
                                  String weakestPlatform, Integer weakestConfidence,
                                  List<String> readyPlatforms, Evidence evidence, String caveat) {

        public Map<String, Object> snapshot() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("bestPlatform", bestPlatform);
            out.put("bestConfidence", bestConfidence);
            out.put("weakestPlatform", weakestPlatform);
            out.put("weakestConfidence", weakestConfidence);
            out.put("readyPlatforms", readyPlatforms == null ? List.of() : readyPlatforms);
            out.put("evidence", evidence == null ? null : evidence.snapshot());
            out.put("caveat", caveat);
            return out;
        }
    }

    /** True when nothing in this snapshot is actionable — the honest "no verified data" case. */
    public boolean isEmpty() {
        return resume == null && countries.isEmpty() && companies.isEmpty()
                && skills.isEmpty() && ats == null;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", generatedAt == null ? null : generatedAt.toString());
        out.put("resume", resume == null ? null : resume.snapshot());
        out.put("countries", countries.stream().map(DimensionFinding::snapshot).toList());
        out.put("companies", companies.stream().map(DimensionFinding::snapshot).toList());
        out.put("skills", skills.stream().map(DimensionFinding::snapshot).toList());
        out.put("ats", ats == null ? null : ats.snapshot());
        out.put("notes", notes);
        return out;
    }
}
