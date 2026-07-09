package ai.careerpilot.autopilot.research;

import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.ai.ChatMessage;
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
 * Phase 7.8 — generates concise company research notes to support interview prep, reusing the
 * existing {@link AiGatewayService} (never a new AI seam). Grounded only in the job posting the
 * platform already holds — it does not scrape sites or violate robots/terms; when the LLM is
 * unavailable it returns empty (the caller degrades to human review). Gated by
 * {@code company.research.enabled} (default off). Transient result — no new table.
 */
@Service
public class CompanyResearchEngine {

    private static final Logger log = LoggerFactory.getLogger(CompanyResearchEngine.class);

    private static final String SYSTEM_PROMPT = """
            You are an interview-prep research assistant. Using ONLY the company name and job posting
            provided, write a concise briefing (under 200 words) covering what the company likely does,
            the role's focus, and 3-4 things a candidate should research further before interviewing.
            Do not invent funding numbers, headcounts, or facts not implied by the posting. Plain text only.
            """;

    private final AiGatewayService ai;
    private final JobRepository jobs;
    private final boolean enabled;

    public CompanyResearchEngine(AiGatewayService ai, JobRepository jobs,
                                 @Value("${company.research.enabled:false}") boolean enabled) {
        this.ai = ai;
        this.jobs = jobs;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public record CompanyResearch(String company, String summary) {}

    /** Research the company behind a job. Empty when disabled, the job is missing, or the LLM fails. */
    public Optional<CompanyResearch> research(UUID jobId) {
        if (!enabled) return Optional.empty();
        Job job = jobs.findById(jobId).orElse(null);
        if (job == null) return Optional.empty();
        try {
            String summary = ai.chat(List.of(ChatMessage.user(prompt(job))), SYSTEM_PROMPT);
            return Optional.of(new CompanyResearch(job.getCompany(), summary));
        } catch (Exception e) {
            log.warn("COMPANY_RESEARCH error job={}: {}", jobId, e.toString());
            return Optional.empty();
        }
    }

    private static String prompt(Job job) {
        return "COMPANY: " + nz(job.getCompany()) + "\nROLE: " + nz(job.getTitle())
                + "\nPOSTING:\n" + truncate(nz(job.getDescription()), 4000);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
