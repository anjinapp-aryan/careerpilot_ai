package ai.careerpilot.autopilot.prep;

import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.ai.ChatMessage;
import ai.careerpilot.autopilot.research.CompanyResearchEngine;
import ai.careerpilot.companyintel.CompanyKnowledgeService;
import ai.careerpilot.companyintel.KnowledgeSection;
import ai.careerpilot.companyintel.KnowledgeSource;
import ai.careerpilot.domain.Job;
import ai.careerpilot.repo.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 7.9 — generates a structured interview-preparation plan for a job, reusing the existing
 * {@link AiGatewayService} and the Phase 7.8 {@link CompanyResearchEngine} for company context. It
 * grounds the plan in the real job posting (and company research when available); when the LLM is
 * unavailable it returns empty rather than fabricating a plan. Gated by {@code interview.prep.enabled}
 * (default off). Transient result — no new table.
 */
@Service
public class InterviewPreparationService {

    private static final Logger log = LoggerFactory.getLogger(InterviewPreparationService.class);

    private static final String SYSTEM_PROMPT = """
            You are an expert technical interview coach. Produce a focused interview-prep plan for the
            given role, grounded in the job posting and any company context. Include: likely role/tech
            questions, behavioral questions, key topics to revise, and a short study plan. Do not invent
            requirements not implied by the posting. Plain text with clear headings.
            """;

    private final AiGatewayService ai;
    private final JobRepository jobs;
    private final CompanyResearchEngine research;
    private final CompanyKnowledgeService companyKnowledge;
    private final boolean enabled;

    public InterviewPreparationService(AiGatewayService ai, JobRepository jobs, CompanyResearchEngine research,
                                       CompanyKnowledgeService companyKnowledge,
                                       @Value("${interview.prep.enabled:false}") boolean enabled) {
        this.ai = ai;
        this.jobs = jobs;
        this.research = research;
        this.companyKnowledge = companyKnowledge;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public record InterviewPlan(UUID jobId, String plan) {}

    /** Build a prep plan for (user, job). Empty when disabled, the job is missing, or the LLM fails. */
    public Optional<InterviewPlan> prepare(UUID userId, UUID jobId) {
        if (!enabled) return Optional.empty();
        Job job = jobs.findById(jobId).orElse(null);
        if (job == null) return Optional.empty();
        String companyContext = research.research(jobId).map(CompanyResearchEngine.CompanyResearch::summary).orElse("");
        // Phase 7.13 — reuse persisted interview knowledge from the Company Knowledge Graph (empty
        // when the graph is dark), so prep gets richer every time this company is interviewed with.
        String priorInterviewKnowledge = companyKnowledge.findByName(userId, job.getCompany())
                .map(k -> companyKnowledge.sections(k).getOrDefault(KnowledgeSection.INTERVIEW_PROCESS.key(), ""))
                .orElse("");
        try {
            String plan = ai.chat(List.of(ChatMessage.user(prompt(job, companyContext, priorInterviewKnowledge))), SYSTEM_PROMPT);
            log.info("INTERVIEW_PREP generated user={} job={}", userId, jobId);
            // Persist the generated plan + research brief into the knowledge graph (silent no-op when
            // company.knowledge.enabled is off) so Interview Prep and Copilot can reuse them later.
            companyKnowledge.recordSections(userId, job.getCompany(),
                    java.util.Map.of(KnowledgeSection.INTERVIEW_PROCESS, plan,
                            KnowledgeSection.PROFILE, companyContext),
                    KnowledgeSource.INTERVIEW_PREP, jobId);
            return Optional.of(new InterviewPlan(jobId, plan));
        } catch (Exception e) {
            log.warn("INTERVIEW_PREP error user={} job={}: {}", userId, jobId, e.toString());
            return Optional.empty();
        }
    }

    private static String prompt(Job job, String companyContext, String priorInterviewKnowledge) {
        StringBuilder sb = new StringBuilder();
        sb.append("ROLE: ").append(nz(job.getTitle())).append(" @ ").append(nz(job.getCompany())).append('\n');
        if (!companyContext.isBlank()) sb.append("COMPANY CONTEXT:\n").append(companyContext).append('\n');
        if (!priorInterviewKnowledge.isBlank()) {
            sb.append("PRIOR INTERVIEW KNOWLEDGE (observed for this company earlier):\n")
                    .append(truncate(priorInterviewKnowledge, 2000)).append('\n');
        }
        sb.append("POSTING:\n").append(truncate(nz(job.getDescription()), 4000));
        return sb.toString();
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
