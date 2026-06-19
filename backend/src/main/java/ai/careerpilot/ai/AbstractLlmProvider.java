package ai.careerpilot.ai;

import java.util.List;

/**
 * Base class that implements every feature helper in terms of the low-level
 * {@link #chat(List, String, double)} primitive, so concrete providers only
 * implement transport (chat / streamChat / health). Prompt templates live here
 * once and are shared by all providers.
 */
public abstract class AbstractLlmProvider implements LlmProvider {

    /** Temperature used for the feature helpers (deterministic-leaning). */
    protected double featureTemperature() {
        return 0.3;
    }

    private String ask(String system, String userPrompt) {
        return chat(List.of(ChatMessage.user(userPrompt)), system, featureTemperature());
    }

    @Override
    public String generateResumeFeedback(String resumeText) {
        return ask(
                "You are an expert resume reviewer and career coach. Be specific, prioritized, and actionable. Use markdown.",
                "Review the following resume and return the highest-impact improvements: strong action verbs, "
                        + "quantified achievements, role-aligned keywords, and structure. Show before → after for weak bullets.\n\n"
                        + "RESUME:\n" + resumeText);
    }

    @Override
    public String generateCoverLetter(String resumeText, String jobDescription) {
        return ask(
                "You are a professional career writer. Produce a concise, tailored, confident cover letter. No clichés.",
                "Write a one-page cover letter tailored to the job, grounded in the candidate's real experience.\n\n"
                        + "JOB DESCRIPTION:\n" + jobDescription + "\n\nRESUME:\n" + resumeText);
    }

    @Override
    public String generateInterviewQuestions(String resumeText, String jobDescription) {
        return ask(
                "You are a senior technical interviewer. Be realistic and role-specific. Use markdown.",
                "Generate the most likely interview questions for this role, grouped by category (technical, behavioral, "
                        + "system/role-specific), with a short 'what they're really assessing' note for each.\n\n"
                        + "JOB DESCRIPTION:\n" + jobDescription + "\n\nRESUME:\n" + resumeText);
    }

    @Override
    public String generateAtsSuggestions(String resumeText, String jobDescription) {
        return ask(
                "You are an ATS optimization specialist. Be concrete about keywords and formatting. Use markdown.",
                "Assess how well this resume will pass ATS for the target job. List missing keywords/skills, formatting "
                        + "risks, and the top fixes. End with an estimated ATS readiness band (Low/Medium/High).\n\n"
                        + "JOB DESCRIPTION:\n" + jobDescription + "\n\nRESUME:\n" + resumeText);
    }

    @Override
    public String generateCareerAdvice(String context) {
        return ask(
                "You are a senior career strategist and mentor. Be direct, practical, and encouraging. Use markdown.",
                "Given the candidate's context, provide focused career advice and a prioritized next-steps plan.\n\n"
                        + "CONTEXT:\n" + context);
    }

    @Override
    public String generateJobMatchInsights(String resumeText, String jobDescription) {
        return ask(
                "You are a technical recruiter. Assess fit honestly across skills, seniority, and domain. Use markdown.",
                "Compare the candidate against the job. Give a qualitative match assessment, strongest alignment points, "
                        + "and the gaps to close before applying.\n\n"
                        + "JOB DESCRIPTION:\n" + jobDescription + "\n\nRESUME:\n" + resumeText);
    }

    @Override
    public String generateWorkflowRecommendations(String context) {
        return ask(
                "You are CareerPilot's AI strategist. Turn the workflow results into clear next actions. Use markdown.",
                "Based on the workflow run context, explain what the results mean and recommend the highest-leverage "
                        + "next steps.\n\nCONTEXT:\n" + context);
    }
}
