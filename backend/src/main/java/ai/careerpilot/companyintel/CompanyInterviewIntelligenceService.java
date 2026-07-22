package ai.careerpilot.companyintel;

import ai.careerpilot.domain.Interview;
import ai.careerpilot.domain.InterviewFeedback;
import ai.careerpilot.domain.Job;
import ai.careerpilot.jobdiscovery.JobScoring;
import ai.careerpilot.repo.InterviewFeedbackRepository;
import ai.careerpilot.repo.InterviewRepository;
import ai.careerpilot.repo.JobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phase 7.17.2 — Company Interview Intelligence. Aggregates REAL {@link Interview}/{@link
 * InterviewFeedback} rows per (user, company) — a genuine gap: {@code CompanyKnowledgeWorker} only
 * ever forwarded a generic milestone label ("learning event INTERVIEW_COMPLETED") into {@code
 * CompanyTimelineEvent}, never the structured round/result/duration/feedback data. This class reads
 * the SAME entities directly instead — no new persistence, no AI generation.
 *
 * <p>Two honesty notes, load-bearing for every field this service returns:
 * <ul>
 *   <li><b>Pass rate is derived from candidate self-assessed feedback ratings</b> (see {@code
 *       InterviewService#addFeedback}'s rating→result mapping), never from a confirmed employer
 *       decision — no such signal exists anywhere in this codebase. Every response says so.</li>
 *   <li>The spec asks for "coding question frequency," "system design frequency," and "behavioral
 *       questions" as distinct metrics. {@link Interview#getInterviewType()} only has 7 flat values
 *       (RECRUITER/TECHNICAL/SYSTEM_DESIGN/MANAGER/HR/FINAL/OFFER) — there is no BEHAVIORAL type and
 *       no coding-vs-non-coding split within TECHNICAL. Rather than invent a distinction the data
 *       doesn't support, this service reports the real {@code roundsByType} breakdown plus explicit
 *       notes on what isn't distinguishable.</li>
 * </ul>
 *
 * <p>Ships dark via {@code company.interview-intelligence.enabled}, independent of {@code
 * interview.tracking.enabled} (the underlying data may exist from before this flag is flipped, but
 * reading it is a separate decision from recording it).
 */
@Service
public class CompanyInterviewIntelligenceService {

    private final InterviewRepository interviews;
    private final InterviewFeedbackRepository feedbackRepo;
    private final JobRepository jobs;
    private final boolean enabled;

    public CompanyInterviewIntelligenceService(InterviewRepository interviews,
                                               InterviewFeedbackRepository feedbackRepo,
                                               JobRepository jobs,
                                               @Value("${company.interview-intelligence.enabled:false}") boolean enabled) {
        this.interviews = interviews;
        this.feedbackRepo = feedbackRepo;
        this.jobs = jobs;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Optional<Map<String, Object>> forCompany(UUID userId, String companyName) {
        if (!enabled || companyName == null || companyName.isBlank()) return Optional.empty();
        String normalized = CompanyNameNormalizer.normalize(companyName);

        List<Interview> all = interviews.findByUserIdOrderByCreatedAtDesc(userId);
        Set<UUID> jobIds = all.stream().map(Interview::getJobId).collect(Collectors.toSet());
        Map<UUID, Job> jobById = jobs.findAllById(jobIds).stream()
                .collect(Collectors.toMap(Job::getId, j -> j, (a, b) -> a));

        List<Interview> forCompany = all.stream()
                .filter(i -> {
                    Job job = jobById.get(i.getJobId());
                    return job != null && normalized.equals(CompanyNameNormalizer.normalize(job.getCompany()));
                })
                .toList();

        int roundCount = forCompany.size();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("companyName", companyName);
        out.put("interviewRounds", roundCount);
        if (roundCount == 0) {
            out.put("confidence", "NONE");
            out.put("confidenceEvidence", "no interview rounds recorded for this company yet");
            return Optional.of(out);
        }

        Map<String, Long> roundsByType = forCompany.stream()
                .collect(Collectors.groupingBy(Interview::getInterviewType, Collectors.counting()));
        out.put("roundsByType", roundsByType);
        out.put("behavioralRoundsNote", "not distinguished — Interview has no BEHAVIORAL type; "
                + "closest observed types are MANAGER/HR (see roundsByType)");
        out.put("codingRoundsNote", "not distinguished — TECHNICAL interviews may or may not include "
                + "coding questions; roundsByType.TECHNICAL is the closest available proxy");

        long passed = forCompany.stream().filter(i -> Interview.RESULT_PASSED.equals(i.getResult())).count();
        long failed = forCompany.stream().filter(i -> Interview.RESULT_FAILED.equals(i.getResult())).count();
        long decided = passed + failed;
        out.put("passRate", decided == 0 ? null : Math.round(passed * 100.0 / decided));
        out.put("passRateEvidence", decided + " of " + roundCount + " rounds have a recorded PASSED/FAILED outcome");
        out.put("passRateNote", "derived from the candidate's own feedback rating for each round — "
                + "a self-assessment, not a confirmed employer decision");

        List<Integer> durations = forCompany.stream()
                .map(Interview::getDurationMinutes).filter(Objects::nonNull).toList();
        out.put("avgDurationMinutes", durations.isEmpty() ? null
                : durations.stream().mapToInt(Integer::intValue).average().orElse(0.0));
        out.put("avgDurationEvidence", durations.size() + " of " + roundCount + " rounds have a recorded duration");

        List<UUID> interviewIds = forCompany.stream().map(Interview::getId).toList();
        List<InterviewFeedback> feedbackRows = feedbackRepo.findByInterviewIdIn(interviewIds);
        List<Integer> ratings = feedbackRows.stream().map(InterviewFeedback::getRating).filter(Objects::nonNull).toList();
        out.put("avgFeedbackRating", ratings.isEmpty() ? null
                : ratings.stream().mapToInt(Integer::intValue).average().orElse(0.0));
        out.put("positiveFeedbackCount", ratings.stream().filter(r -> r >= 4).count());
        out.put("negativeFeedbackCount", ratings.stream().filter(r -> r <= 2).count());
        out.put("feedbackEvidence", feedbackRows.size() + " feedback entries across " + roundCount + " rounds");

        // Deterministic keyword scan over real feedback text, reusing JobScoring's existing
        // vocabulary rather than inventing a second one — never AI-generated.
        Map<String, Long> techMentions = new LinkedHashMap<>();
        for (InterviewFeedback f : feedbackRows) {
            if (f.getFeedback() == null) continue;
            String text = f.getFeedback().toLowerCase();
            for (String term : JobScoring.SKILL_VOCABULARY) {
                if (text.contains(term)) techMentions.merge(term, 1L, Long::sum);
            }
        }
        List<String> topTechnologies = techMentions.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10).map(Map.Entry::getKey).toList();
        out.put("frequentlyMentionedTechnologies", topTechnologies);
        out.put("frequentlyMentionedTechnologiesEvidence",
                "keyword matches across " + feedbackRows.size() + " feedback entries' free text");

        String confidence = roundCount >= 8 ? "HIGH" : roundCount >= 3 ? "MEDIUM" : "LOW";
        out.put("confidence", confidence);
        out.put("confidenceEvidence", roundCount + " observed interview rounds for this company");
        out.put("sampleSize", roundCount);
        return Optional.of(out);
    }
}
