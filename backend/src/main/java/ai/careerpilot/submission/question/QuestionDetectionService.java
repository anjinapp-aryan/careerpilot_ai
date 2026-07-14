package ai.careerpilot.submission.question;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 7.16 — deterministic keyword classifier for a supplied application question, plus the
 * fixed set of 11 common questions the orchestrator always runs so the approval screen always has
 * something to show (per the spec's "always run so the approval screen always has something to
 * show" note). No LLM — same convention as {@code StoryRecommendationEngine}'s deterministic
 * keyword scoring.
 */
@Service
public class QuestionDetectionService {

    /** The 11 spec'd common questions, keyed by category, always asked for a new session. */
    public List<Map.Entry<String, QuestionCategory>> commonQuestions() {
        Map<String, QuestionCategory> qs = new LinkedHashMap<>();
        qs.put("Why are you interested in this role?", QuestionCategory.WHY_ROLE);
        qs.put("Tell us about yourself.", QuestionCategory.ABOUT_YOU);
        qs.put("Describe a time you led a team or project.", QuestionCategory.LEADERSHIP);
        qs.put("Describe a conflict you resolved at work.", QuestionCategory.CONFLICT);
        qs.put("Tell us about a time you failed and what you learned.", QuestionCategory.FAILURE);
        qs.put("What is your proudest professional achievement?", QuestionCategory.ACHIEVEMENT);
        qs.put("What are your salary expectations?", QuestionCategory.SALARY);
        qs.put("What is your notice period?", QuestionCategory.NOTICE_PERIOD);
        qs.put("Do you require visa sponsorship?", QuestionCategory.VISA);
        qs.put("Are you willing to relocate for this role?", QuestionCategory.RELOCATION);
        qs.put("What is your remote-work preference?", QuestionCategory.REMOTE_PREFERENCE);
        return List.copyOf(qs.entrySet());
    }

    /** Classify arbitrary supplied question text. Never throws; unmatched text is {@code OTHER}. */
    public QuestionCategory classify(String questionText) {
        if (questionText == null || questionText.isBlank()) return QuestionCategory.OTHER;
        String q = questionText.toLowerCase(Locale.ROOT);

        if (containsAny(q, "salary", "compensation", "pay expectation", "expected ctc")) return QuestionCategory.SALARY;
        if (containsAny(q, "notice period", "start date", "when can you start", "availability")) return QuestionCategory.NOTICE_PERIOD;
        if (containsAny(q, "visa", "sponsorship", "work authorization", "authorized to work")) return QuestionCategory.VISA;
        if (containsAny(q, "relocate", "relocation", "willing to move")) return QuestionCategory.RELOCATION;
        if (containsAny(q, "remote", "hybrid", "work from home", "onsite preference")) return QuestionCategory.REMOTE_PREFERENCE;
        if (containsAny(q, "lead a team", "led a team", "leadership", "manage a team", "managing a team")) return QuestionCategory.LEADERSHIP;
        if (containsAny(q, "conflict", "disagreement", "disagree with")) return QuestionCategory.CONFLICT;
        if (containsAny(q, "fail", "mistake you made", "went wrong")) return QuestionCategory.FAILURE;
        if (containsAny(q, "proudest", "achievement", "accomplishment", "greatest success")) return QuestionCategory.ACHIEVEMENT;
        if (containsAny(q, "why are you interested", "why do you want", "why this role", "why this company", "why us")) return QuestionCategory.WHY_ROLE;
        if (containsAny(q, "tell us about yourself", "tell me about yourself", "about you", "introduce yourself")) return QuestionCategory.ABOUT_YOU;

        return QuestionCategory.OTHER;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }
}
