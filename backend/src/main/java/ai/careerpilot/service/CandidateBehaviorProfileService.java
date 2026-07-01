package ai.careerpilot.service;

import ai.careerpilot.domain.CandidateBehaviorProfile;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.JobAiEnrichment;
import ai.careerpilot.domain.RecommendationFeedback;
import ai.careerpilot.jobdiscovery.JobTaxonomy;
import ai.careerpilot.repo.CandidateBehaviorProfileRepository;
import ai.careerpilot.repo.JobAiEnrichmentRepository;
import ai.careerpilot.repo.JobRepository;
import ai.careerpilot.repo.RecommendationFeedbackRepository;
import ai.careerpilot.service.profile.JsonLists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Phase 2C-5 (Step 7) — rebuilds a user's {@link CandidateBehaviorProfile} by deterministically
 * aggregating their {@code recommendation_feedback} (2C-4): the role/country/work-mode/domain values
 * they tend to accept (APPROVE/SAVE/APPLY_LATER → preferred_*) vs decline (REJECT/IGNORE → rejected_*).
 *
 * <p>No LLM — pure counting + top-N, so a rebuild is idempotent and cheap. A value can legitimately
 * appear in both preferred and rejected (the user accepted one remote role and rejected another); the
 * buckets are independent frequency rankings, not a net score. Gated by the same
 * {@code recommendation.feedback.enabled} flag that produces the feedback it reads.
 */
@Service
public class CandidateBehaviorProfileService {

    private static final Logger log = LoggerFactory.getLogger(CandidateBehaviorProfileService.class);

    /** How many values to keep per bucket. */
    private static final int TOP_N = 12;
    /** Feedback actions that count as a positive (preferred) signal; the rest are negative. */
    private static final Set<String> POSITIVE = Set.of("APPROVE", "SAVE", "APPLY_LATER");

    private final RecommendationFeedbackRepository feedback;
    private final CandidateBehaviorProfileRepository profiles;
    private final JobRepository jobs;
    private final JobTaxonomy taxonomy;
    private final JobAiEnrichmentRepository enrichment;
    private final boolean enabled;

    public CandidateBehaviorProfileService(RecommendationFeedbackRepository feedback,
                                           CandidateBehaviorProfileRepository profiles,
                                           JobRepository jobs,
                                           JobTaxonomy taxonomy,
                                           JobAiEnrichmentRepository enrichment,
                                           @Value("${recommendation.feedback.enabled:false}") boolean enabled) {
        this.feedback = feedback;
        this.profiles = profiles;
        this.jobs = jobs;
        this.taxonomy = taxonomy;
        this.enrichment = enrichment;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Optional<CandidateBehaviorProfile> get(UUID userId) {
        return profiles.findById(userId);
    }

    /** Recompute and upsert this user's behavior profile from their entire feedback history. */
    @Transactional
    public CandidateBehaviorProfile rebuild(UUID userId) {
        List<RecommendationFeedback> rows = feedback.findByUserIdOrderByCreatedAtDesc(userId);

        Map<String, Integer> roleP = new HashMap<>(), roleN = new HashMap<>();
        Map<String, Integer> countryP = new HashMap<>(), countryN = new HashMap<>();
        Map<String, Integer> modeP = new HashMap<>(), modeN = new HashMap<>();
        Map<String, Integer> domainP = new HashMap<>(), domainN = new HashMap<>();

        if (!rows.isEmpty()) {
            List<UUID> jobIds = rows.stream().map(RecommendationFeedback::getJobId).distinct().toList();
            Map<UUID, Job> jobsById = new HashMap<>();
            jobs.findAllById(jobIds).forEach(j -> jobsById.put(j.getId(), j));
            Map<UUID, List<String>> domainsById = new HashMap<>();
            for (JobAiEnrichment e : enrichment.findByJobIdIn(jobIds)) {
                domainsById.put(e.getJobId(), JsonLists.toList(e.getDomainsJson()));
            }

            for (RecommendationFeedback fb : rows) {
                Job job = jobsById.get(fb.getJobId());
                if (job == null) continue; // feedback outlived its job
                boolean positive = POSITIVE.contains(fb.getAction());
                addAll(positive ? roleP : roleN, taxonomy.roleFamilies(job.getTitle()));
                add(positive ? countryP : countryN, job.getCountry());
                add(positive ? modeP : modeN, job.getRemoteType());
                addAll(positive ? domainP : domainN, domainsById.getOrDefault(fb.getJobId(), List.of()));
            }
        }

        CandidateBehaviorProfile p = profiles.findById(userId)
                .orElseGet(() -> CandidateBehaviorProfile.builder().userId(userId).build());
        p.setPreferredRoles(topN(roleP));
        p.setRejectedRoles(topN(roleN));
        p.setPreferredCountries(topN(countryP));
        p.setRejectedCountries(topN(countryN));
        p.setPreferredWorkModes(topN(modeP));
        p.setRejectedWorkModes(topN(modeN));
        p.setPreferredDomains(topN(domainP));
        p.setRejectedDomains(topN(domainN));
        CandidateBehaviorProfile saved = profiles.save(p);
        log.info("BEHAVIOR_PROFILE rebuilt user={} feedbackRows={}", userId, rows.size());
        return saved;
    }

    private static void add(Map<String, Integer> counts, String value) {
        if (value == null) return;
        String v = value.trim();
        if (!v.isEmpty()) counts.merge(v, 1, Integer::sum);
    }

    private static void addAll(Map<String, Integer> counts, Collection<String> values) {
        if (values != null) values.forEach(v -> add(counts, v));
    }

    /** Top-N values by frequency desc, then value asc (deterministic), comma-joined; null when empty. */
    private static String topN(Map<String, Integer> counts) {
        if (counts.isEmpty()) return null;
        return counts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(TOP_N)
                .map(Map.Entry::getKey)
                .reduce((a, b) -> a + "," + b)
                .orElse(null);
    }
}
