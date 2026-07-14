package ai.careerpilot.story.extractor;

import ai.careerpilot.domain.Application;
import ai.careerpilot.domain.Resume;
import ai.careerpilot.repo.ApplicationRepository;
import ai.careerpilot.repo.CompanyKnowledgeRepository;
import ai.careerpilot.repo.ResumeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Phase 7.15 — pulls candidate story material read-only from existing platform data: the latest
 * Resume (parsed text + extracted skills), Application History (status/notes as achievement
 * signals), and Company Research (via {@code CompanyKnowledgeRepository}, when Company
 * Intelligence is enabled). Never duplicates extraction logic from those systems — it only reads
 * their repositories. Interview-specific transcript data has no existing repository to read from
 * in this codebase today (no interview-transcript table), so that source is a deliberate no-op
 * until one exists.
 */
@Component
public class StoryExtractionEngine {

    private final ResumeRepository resumes;
    private final ApplicationRepository applications;
    private final CompanyKnowledgeRepository companyKnowledge;
    private final boolean enabled;

    public StoryExtractionEngine(ResumeRepository resumes, ApplicationRepository applications,
                                 CompanyKnowledgeRepository companyKnowledge,
                                 @Value("${story.extraction.enabled:false}") boolean enabled) {
        this.resumes = resumes;
        this.applications = applications;
        this.companyKnowledge = companyKnowledge;
        this.enabled = enabled;
    }

    public boolean isEnabled() { return enabled; }

    /** Raw, unstructured material to seed STAR generation for one user. */
    public record RawMaterial(String resumeText, String resumeSkills, List<String> applicationHighlights,
                              List<String> companyContext) {
        public boolean isEmpty() {
            return (resumeText == null || resumeText.isBlank())
                    && applicationHighlights.isEmpty() && companyContext.isEmpty();
        }
    }

    public RawMaterial extract(UUID userId) {
        if (!enabled) return new RawMaterial(null, null, List.of(), List.of());

        String resumeText = null;
        String resumeSkills = null;
        List<Resume> userResumes = resumes.findByUserIdOrderByCreatedAtDesc(userId);
        if (!userResumes.isEmpty()) {
            Resume latest = userResumes.get(0);
            resumeText = latest.getParsedText();
            resumeSkills = latest.getExtractedSkillsJson();
        }

        List<Application> apps = applications.findByUserIdOrderByCreatedAtDesc(userId);
        List<String> highlights = apps.stream()
                .filter(a -> "OFFER".equalsIgnoreCase(a.getStatus()) || "INTERVIEWING".equalsIgnoreCase(a.getStatus()))
                .limit(10)
                .map(a -> "Application status=" + a.getStatus()
                        + (a.getNotes() != null && !a.getNotes().isBlank() ? "; notes=" + a.getNotes() : ""))
                .toList();

        List<String> companyContext = companyKnowledge.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .limit(5)
                .map(c -> c.getCompanyName() + " (industry=" + c.getIndustry() + ")")
                .toList();

        return new RawMaterial(resumeText, resumeSkills, highlights, companyContext);
    }
}
