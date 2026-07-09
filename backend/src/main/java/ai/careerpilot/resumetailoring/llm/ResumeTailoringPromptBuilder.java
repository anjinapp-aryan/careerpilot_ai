package ai.careerpilot.resumetailoring.llm;

import ai.careerpilot.ai.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds the system + user prompt for one resume-tailoring generation. Explicitly instructs the
 * model to reorder/promote/optimize only — never to invent experience, skills, certifications,
 * companies, or projects (Phase 2D.1 Step 4/6 rules). {@link ResumeTailoringValidator} is the
 * deterministic backstop that catches violations the prompt alone can't guarantee.
 */
@Component
public class ResumeTailoringPromptBuilder {

    private static final String SYSTEM_PROMPT = """
            You are an elite resume tailoring specialist. Rewrite the candidate's resume to better
            match a specific target job, using ONLY facts already present in the original resume and
            candidate profile.

            ALLOWED changes:
            - Reorder experience, skills, or projects to foreground what's most relevant to the job.
            - Promote/emphasize skills, projects, or achievements the candidate already has that match
              the job.
            - Rewrite the professional summary and responsibility bullet points to use keywords and
              phrasing aligned with the job description.
            - Improve ATS keyword coverage using only terms that describe real, existing experience.

            STRICTLY FORBIDDEN:
            - Never invent experience, employers, job titles, or dates not in the original resume.
            - Never invent skills, technologies, or tools the candidate has not listed or demonstrated.
            - Never invent certifications, degrees, or credentials.
            - Never invent projects or achievements.
            - Never invent metrics/numbers not present in the original text.

            Output ONLY the tailored resume text. No preamble, no explanation, no markdown fences.
            """;

    public List<ChatMessage> buildMessages(TailoringContext ctx) {
        return buildMessages(ctx, List.of());
    }

    /**
     * Phase 7.13 overload — appends the target company's OBSERVED keyword demand (from the Company
     * Knowledge Graph) as emphasis hints. An empty list (graph dark or company unknown) produces a
     * prompt byte-for-byte identical to the original {@link #buildMessages(TailoringContext)}.
     */
    public List<ChatMessage> buildMessages(TailoringContext ctx, List<String> companyKeywordHints) {
        return List.of(new ChatMessage("user", buildUserPrompt(ctx, companyKeywordHints)));
    }

    public String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    private String buildUserPrompt(TailoringContext ctx, List<String> companyKeywordHints) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ORIGINAL RESUME ===\n").append(nullToEmpty(ctx.originalResumeText())).append("\n\n");

        sb.append("=== TARGET JOB ===\n");
        sb.append("Title: ").append(nullToEmpty(ctx.jobTitle())).append("\n");
        sb.append("Company: ").append(nullToEmpty(ctx.jobCompany())).append("\n");
        if (ctx.roleFamily() != null) sb.append("Role family: ").append(ctx.roleFamily()).append("\n");
        if (ctx.jobDomain() != null) sb.append("Domain: ").append(ctx.jobDomain()).append("\n");
        if (ctx.jobCountry() != null) sb.append("Location: ").append(ctx.jobCountry()).append("\n");
        if (!ctx.jobSkills().isEmpty()) sb.append("Required skills: ").append(String.join(", ", ctx.jobSkills())).append("\n");
        sb.append("Description:\n").append(nullToEmpty(ctx.jobDescription())).append("\n\n");

        sb.append("=== CANDIDATE PROFILE (facts you may draw on, never exceed) ===\n");
        if (ctx.yearsExperience() != null) sb.append("Years of experience: ").append(ctx.yearsExperience()).append("\n");
        if (!ctx.profileSkills().isEmpty()) sb.append("Known skills: ").append(String.join(", ", ctx.profileSkills())).append("\n");
        if (!ctx.technologies().isEmpty()) sb.append("Technologies: ").append(String.join(", ", ctx.technologies())).append("\n");
        if (!ctx.certifications().isEmpty()) sb.append("Certifications: ").append(String.join(", ", ctx.certifications())).append("\n");
        if (!ctx.targetRoles().isEmpty()) sb.append("Target roles: ").append(String.join(", ", ctx.targetRoles())).append("\n");

        if (!ctx.preferredRoles().isEmpty() || !ctx.preferredWorkModes().isEmpty()) {
            sb.append("\n=== BEHAVIOR SIGNALS (roles/modes this candidate tends to accept) ===\n");
            if (!ctx.preferredRoles().isEmpty()) sb.append("Preferred roles: ").append(String.join(", ", ctx.preferredRoles())).append("\n");
            if (!ctx.preferredWorkModes().isEmpty()) sb.append("Preferred work modes: ").append(String.join(", ", ctx.preferredWorkModes())).append("\n");
        }

        if (ctx.matchingSkills() != null || ctx.missingSkills() != null || ctx.resumeImprovements() != null) {
            sb.append("\n=== PRIOR MATCH EXPLANATION (for context only) ===\n");
            if (ctx.matchingSkills() != null) sb.append("Matching skills: ").append(ctx.matchingSkills()).append("\n");
            if (ctx.missingSkills() != null) sb.append("Missing skills (do NOT claim these): ").append(ctx.missingSkills()).append("\n");
            if (ctx.resumeImprovements() != null) sb.append("Suggested improvements: ").append(ctx.resumeImprovements()).append("\n");
        }

        if (companyKeywordHints != null && !companyKeywordHints.isEmpty()) {
            sb.append("\n=== COMPANY KEYWORD SIGNALS (observed demand at this company; emphasize ONLY where the candidate already has the skill) ===\n");
            sb.append(String.join(", ", companyKeywordHints)).append("\n");
        }

        sb.append("\nProduce the tailored resume now, following all allowed/forbidden rules above.");
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
