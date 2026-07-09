package ai.careerpilot.companyintel;

import ai.careerpilot.domain.CompanyKnowledge;
import ai.careerpilot.domain.CompanyRelationship;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.JobAiEnrichment;
import ai.careerpilot.jobdiscovery.CandidateSignalResolver;
import ai.careerpilot.repo.CompanyKnowledgeRepository;
import ai.careerpilot.repo.CompanyRelationshipRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase 7.13 — the single entry point for reading and evolving per-user company knowledge. Every
 * write comes from a real platform artifact (job rows, research/prep output, learning outcomes) —
 * never fabricated. Updates are versioned immutably via {@link KnowledgeVersionManager}, reflected
 * on the timeline, mirrored into the graph, and rescored deterministically. Gated by
 * {@code company.knowledge.enabled} (default off): when dark, every write is a silent no-op and
 * every read returns empty, so no caller needs its own guard.
 */
@Service
public class CompanyKnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(CompanyKnowledgeService.class);

    private final CompanyKnowledgeRepository companies;
    private final CompanyRelationshipRepository relationships;
    private final KnowledgeAggregator aggregator;
    private final KnowledgeVersionManager versionManager;
    private final KnowledgeGraphBuilder graphBuilder;
    private final CompanyTimelineService timeline;
    private final CompanyScoringService scoring;
    private final CompanyIntelMetrics metrics;
    private final CandidateSignalResolver signals;
    private final boolean enabled;

    public CompanyKnowledgeService(CompanyKnowledgeRepository companies,
                                   CompanyRelationshipRepository relationships,
                                   KnowledgeAggregator aggregator,
                                   KnowledgeVersionManager versionManager,
                                   KnowledgeGraphBuilder graphBuilder,
                                   CompanyTimelineService timeline,
                                   CompanyScoringService scoring,
                                   CompanyIntelMetrics metrics,
                                   CandidateSignalResolver signals,
                                   @Value("${company.knowledge.enabled:false}") boolean enabled) {
        this.companies = companies;
        this.relationships = relationships;
        this.aggregator = aggregator;
        this.versionManager = versionManager;
        this.graphBuilder = graphBuilder;
        this.timeline = timeline;
        this.scoring = scoring;
        this.metrics = metrics;
        this.signals = signals;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ── Reads ──

    public Optional<CompanyKnowledge> get(UUID userId, UUID companyKnowledgeId) {
        if (!enabled) return Optional.empty();
        return companies.findById(companyKnowledgeId).filter(c -> userId.equals(c.getUserId()));
    }

    public Optional<CompanyKnowledge> findByName(UUID userId, String companyName) {
        if (!enabled) return Optional.empty();
        return companies.findByUserIdAndNormalizedName(userId, CompanyNameNormalizer.normalize(companyName));
    }

    public List<CompanyKnowledge> search(UUID userId, String query) {
        if (!enabled) return List.of();
        if (query == null || query.isBlank()) return companies.findByUserIdOrderByUpdatedAtDesc(userId);
        return companies.findTop50ByUserIdAndNormalizedNameContainingOrderByUpdatedAtDesc(
                userId, CompanyNameNormalizer.normalize(query));
    }

    public Map<String, String> sections(CompanyKnowledge company) {
        return aggregator.parse(company.getKnowledge());
    }

    // ── Writes (all no-ops when dark) ──

    /**
     * Ingest what a job row already tells us about its company: observed skill demand, salary
     * insight, business domain. First sight of a company creates the row + a DISCOVERED milestone.
     */
    @Transactional
    public Optional<CompanyKnowledge> ingestJob(UUID userId, Job job, JobAiEnrichment enrichment, KnowledgeSource source) {
        if (!enabled || job == null || job.getCompany() == null || job.getCompany().isBlank()) return Optional.empty();
        CompanyKnowledge company = getOrCreate(userId, job.getCompany(), job.getId());
        Map<KnowledgeSection, String> updates = new LinkedHashMap<>();
        if (job.getSkills() != null && !job.getSkills().isBlank()) {
            updates.put(KnowledgeSection.SKILL_DEMAND, "Observed skill demand: " + job.getSkills());
        }
        if (job.getSalaryRange() != null && !job.getSalaryRange().isBlank()) {
            updates.put(KnowledgeSection.SALARY_INSIGHTS, "Observed salary range: " + job.getSalaryRange());
        }
        if (job.getJobFamily() != null && !job.getJobFamily().isBlank()) {
            updates.put(KnowledgeSection.BUSINESS_DOMAIN, "Hires in the " + job.getJobFamily() + " family");
            company.setIndustry(job.getJobFamily());
        }
        applySections(company, updates, source, job.getId());
        graphBuilder.ingest(company, job, enrichment, source);
        if (job.getSalaryMin() != null || job.getSalaryMax() != null) {
            graphBuilder.upsert(company, CompanyRelationship.TYPE_SALARY_BAND, salaryBand(job), source);
        }
        rescore(company);
        metrics.increment("jobIngests");
        return Optional.of(companies.save(company));
    }

    /** Record already-computed knowledge text (research brief, interview plan, tailoring signals). */
    @Transactional
    public Optional<CompanyKnowledge> recordSections(UUID userId, String companyName,
                                                     Map<KnowledgeSection, String> updates,
                                                     KnowledgeSource source, UUID refId) {
        if (!enabled || companyName == null || companyName.isBlank()) return Optional.empty();
        CompanyKnowledge company = getOrCreate(userId, companyName, refId);
        applySections(company, updates, source, refId);
        rescore(company);
        return Optional.of(companies.save(company));
    }

    /** Record a real outcome (application/interview/offer/rejection) against the company. */
    @Transactional
    public Optional<CompanyKnowledge> recordOutcome(UUID userId, String companyName,
                                                    CompanyTimelineEventType type, UUID refId, String detail) {
        if (!enabled || companyName == null || companyName.isBlank()) return Optional.empty();
        CompanyKnowledge company = getOrCreate(userId, companyName, refId);
        timeline.record(company, type, refId, detail);
        String note = Instant.now() + " — " + type.name() + (detail == null || detail.isBlank() ? "" : " (" + detail + ")");
        String existing = aggregator.parse(company.getKnowledge())
                .getOrDefault(KnowledgeSection.LEARNING_HISTORY.key(), "");
        applySections(company,
                Map.of(KnowledgeSection.LEARNING_HISTORY, appendLine(existing, note)),
                KnowledgeSource.LEARNING_ENGINE, refId);
        rescore(company);
        metrics.increment("outcomes");
        return Optional.of(companies.save(company));
    }

    /** Restore an older knowledge snapshot as a NEW head version (history is never deleted). */
    @Transactional
    public Optional<CompanyKnowledge> rollback(UUID userId, UUID companyKnowledgeId, int toVersion) {
        if (!enabled) return Optional.empty();
        CompanyKnowledge company = get(userId, companyKnowledgeId).orElse(null);
        if (company == null) return Optional.empty();
        String restored = versionManager.rollbackKnowledge(companyKnowledgeId, toVersion).orElse(null);
        if (restored == null) return Optional.empty();
        company.setKnowledge(restored);
        company.setKnowledgeVersion(company.getKnowledgeVersion() + 1);
        company.setLastSource(KnowledgeSource.ROLLBACK.name());
        versionManager.snapshot(company, List.of("rollback:v" + toVersion), KnowledgeSource.ROLLBACK);
        timeline.record(company, CompanyTimelineEventType.KNOWLEDGE_UPDATED, null, "rolled back to v" + toVersion);
        rescore(company);
        metrics.increment("rollbacks");
        return Optional.of(companies.save(company));
    }

    // ── Scoring ──

    /** Recompute the 10 deterministic scores from current knowledge, graph and outcome history. */
    public void rescore(CompanyKnowledge company) {
        if (company.getId() == null) return; // scored on the next update once persisted
        Map<String, String> sections = aggregator.parse(company.getKnowledge());
        List<CompanyRelationship> edges = relationships.findByCompanyKnowledgeId(company.getId());
        Map<String, Long> edgeCounts = edges.stream()
                .collect(Collectors.groupingBy(CompanyRelationship::getRelationType, Collectors.counting()));
        Set<String> tech = edges.stream()
                .filter(e -> CompanyRelationship.TYPE_TECHNOLOGY.equals(e.getRelationType())
                        || CompanyRelationship.TYPE_SKILL.equals(e.getRelationType()))
                .map(CompanyRelationship::getTarget)
                .collect(Collectors.toSet());
        List<String> candidateSkills = signals.resolve(company.getUserId())
                .map(CandidateSignalResolver.CandidateMatchSignals::skills)
                .orElse(List.of());

        CompanyScoringService.CompanyStats stats = new CompanyScoringService.CompanyStats(
                sections, edgeCounts,
                timeline.count(company.getId(), CompanyTimelineEventType.APPLICATION_SUBMITTED),
                timeline.count(company.getId(), CompanyTimelineEventType.INTERVIEW_COMPLETED)
                        + timeline.count(company.getId(), CompanyTimelineEventType.INTERVIEW_SCHEDULED),
                timeline.count(company.getId(), CompanyTimelineEventType.OFFER_RECEIVED),
                timeline.count(company.getId(), CompanyTimelineEventType.REJECTED),
                tech, candidateSkills);

        CompanyScoringService.ScoreSet result = scoring.score(stats);
        Map<String, Integer> s = result.scores();
        company.setQualityScore(s.get("qualityScore"));
        company.setInterviewDifficulty(s.get("interviewDifficulty"));
        company.setHiringProbability(s.get("hiringProbability"));
        company.setResumeCompatibility(s.get("resumeCompatibility"));
        company.setTechnologyMatch(s.get("technologyMatch"));
        company.setCareerGrowth(s.get("careerGrowth"));
        company.setSalaryPotential(s.get("salaryPotential"));
        company.setCultureMatch(s.get("cultureMatch"));
        company.setLearningValue(s.get("learningValue"));
        company.setLongTermOpportunity(s.get("longTermOpportunity"));
        company.setScoreExplanations(aggregator.write(result.explanations()));
    }

    // ── Internals ──

    private CompanyKnowledge getOrCreate(UUID userId, String companyName, UUID refId) {
        String normalized = CompanyNameNormalizer.normalize(companyName);
        return companies.findByUserIdAndNormalizedName(userId, normalized).orElseGet(() -> {
            CompanyKnowledge created = companies.save(CompanyKnowledge.builder()
                    .userId(userId)
                    .companyName(companyName.strip())
                    .normalizedName(normalized)
                    .knowledge("{}")
                    .knowledgeVersion(1)
                    .firstDiscoveredAt(Instant.now())
                    .build());
            timeline.record(created, CompanyTimelineEventType.DISCOVERED, refId, "first seen");
            metrics.increment("companiesCreated");
            log.info("COMPANY_INTEL created knowledge row user={} company={}", userId, normalized);
            return created;
        });
    }

    /** Merge sections; when anything actually changed, bump the version and snapshot immutably. */
    private void applySections(CompanyKnowledge company, Map<KnowledgeSection, String> updates,
                               KnowledgeSource source, UUID refId) {
        KnowledgeAggregator.MergeResult merge = aggregator.merge(company.getKnowledge(), updates);
        if (!merge.changed()) return;
        company.setKnowledge(aggregator.write(merge.merged()));
        // First real content keeps version 1 (the row was created empty); later changes bump it.
        boolean firstContent = company.getLastSource() == null;
        if (!firstContent) company.setKnowledgeVersion(company.getKnowledgeVersion() + 1);
        company.setLastSource(source == null ? null : source.name());
        versionManager.snapshot(company, merge.changedSections(), source);
        timeline.record(company, CompanyTimelineEventType.KNOWLEDGE_UPDATED, refId,
                String.join(",", merge.changedSections()));
        metrics.increment("knowledgeUpdates");
    }

    private static String appendLine(String existing, String line) {
        if (existing == null || existing.isBlank()) return line;
        String combined = existing + "\n" + line;
        // Keep the most recent history when the section grows past the aggregator's cap.
        return combined.length() > 7500 ? combined.substring(combined.length() - 7500) : combined;
    }

    /** Same 5-bucket banding as {@code LearningRecommendationBooster.salaryBand}, kept in sync deliberately. */
    private static String salaryBand(Job job) {
        double min = job.getSalaryMin() == null ? 0 : job.getSalaryMin().doubleValue();
        double max = job.getSalaryMax() == null ? 0 : job.getSalaryMax().doubleValue();
        double mid = (min + max) / 2.0;
        if (mid <= 0) return "";
        if (mid < 60_000) return "UNDER_60K";
        if (mid < 100_000) return "60K_100K";
        if (mid < 150_000) return "100K_150K";
        if (mid < 200_000) return "150K_200K";
        return "OVER_200K";
    }
}
