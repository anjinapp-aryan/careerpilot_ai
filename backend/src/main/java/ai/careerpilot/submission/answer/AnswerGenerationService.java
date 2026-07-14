package ai.careerpilot.submission.answer;

import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.ai.ChatMessage;
import ai.careerpilot.domain.StarStory;
import ai.careerpilot.submission.question.QuestionCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Phase 7.16 — generates one grounded answer per detected question via {@link AiGatewayService}
 * (the single backend AI entry point — never a concrete provider). The system prompt explicitly
 * forbids inventing anything not present in the supplied context, mirroring the grounding style of
 * existing Copilot skill handlers (see {@code StoryRecommendationHandler}) and the strict
 * no-fabrication rules already enforced in {@code CoverLetterService}'s system prompt.
 *
 * <p>Context = tailored resume text + application package summary + company brief + candidate's
 * best-fit STAR story (all optional — a missing input just narrows what's available, it never
 * causes fabrication). Never throws; a provider failure yields an honest "unable to generate"
 * answer rather than propagating an exception into the orchestrator.
 */
@Service
public class AnswerGenerationService {

    private static final Logger log = LoggerFactory.getLogger(AnswerGenerationService.class);

    private static final String SYSTEM_PROMPT = """
            You are CareerPilot's application-answer assistant. You draft a candidate's answer to
            one job-application question.

            STRICT GROUNDING RULES — NEVER violate:
            - Answer ONLY using the information given to you below (resume, application package,
              company brief, STAR story). Do not invent skills, employers, titles, metrics, dates,
              certifications, or experience that is not present in that context.
            - If the provided context does not contain enough information to answer well, say so
              explicitly in the answer (e.g. "Based on the information available, I can note that…")
              rather than inventing details to fill the gap.
            - Keep the answer concise (3-6 sentences unless the question is a STAR-style behavioral
              question, which may run longer), professional, and directly responsive to the question.
            """;

    public record GeneratedAnswer(String questionText, QuestionCategory category,
                                  String answerText, String sourceRefs) {}

    public record AnswerContext(String resumeText, String packageSummary, String companyBrief,
                                StarStory story) {
        public static AnswerContext empty() {
            return new AnswerContext(null, null, null, null);
        }
    }

    private final AiGatewayService ai;

    public AnswerGenerationService(AiGatewayService ai) {
        this.ai = ai;
    }

    public GeneratedAnswer generate(UUID userId, UUID jobId, String questionText,
                                    QuestionCategory category, AnswerContext ctx) {
        try {
            String userPrompt = buildUserPrompt(questionText, category, ctx);
            String answer = ai.chat(List.of(ChatMessage.user(userPrompt)), SYSTEM_PROMPT);
            return new GeneratedAnswer(questionText, category, answer, sourceRefs(ctx));
        } catch (Exception e) {
            log.warn("ANSWER_GENERATION error user={} job={} question={}: {}", userId, jobId, questionText, e.toString());
            return new GeneratedAnswer(questionText, category,
                    "Unable to generate an answer at this time — please provide this response manually.",
                    sourceRefs(ctx));
        }
    }

    private static String buildUserPrompt(String questionText, QuestionCategory category, AnswerContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("QUESTION (category=").append(category).append("):\n").append(questionText).append("\n\n");
        sb.append("CONTEXT — use only what is below:\n");
        sb.append("RESUME:\n").append(nullSafe(ctx.resumeText())).append("\n\n");
        sb.append("APPLICATION PACKAGE SUMMARY:\n").append(nullSafe(ctx.packageSummary())).append("\n\n");
        sb.append("COMPANY BRIEF:\n").append(nullSafe(ctx.companyBrief())).append("\n\n");
        if (ctx.story() != null) {
            sb.append("BEST-FIT STAR STORY:\n")
                    .append("Situation: ").append(nullSafe(ctx.story().getSituation())).append('\n')
                    .append("Task: ").append(nullSafe(ctx.story().getTask())).append('\n')
                    .append("Action: ").append(nullSafe(ctx.story().getAction())).append('\n')
                    .append("Result: ").append(nullSafe(ctx.story().getResult())).append('\n');
        } else {
            sb.append("BEST-FIT STAR STORY: none available\n");
        }
        return sb.toString();
    }

    private static String sourceRefs(AnswerContext ctx) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        if (ctx.resumeText() != null) { sb.append("\"resume\":true"); first = false; }
        if (ctx.packageSummary() != null) { if (!first) sb.append(','); sb.append("\"applicationPackage\":true"); first = false; }
        if (ctx.companyBrief() != null) { if (!first) sb.append(','); sb.append("\"companyBrief\":true"); first = false; }
        if (ctx.story() != null) {
            if (!first) sb.append(',');
            sb.append("\"starStoryId\":\"").append(ctx.story().getId()).append('"');
        }
        return sb.append('}').toString();
    }

    private static String nullSafe(String s) {
        return s == null || s.isBlank() ? "n/a" : s;
    }
}
