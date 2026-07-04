package ai.careerpilot.workflow.email;

import ai.careerpilot.domain.ApplicationEmail;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 3A.3 — the pure, deterministic email classifier + extractor. No I/O, no LLM, no external
 * fetch: unit-tested exhaustively. Maps an email's subject+body to one of the
 * {@link ApplicationEmail} categories by keyword precedence
 * (OFFER &gt; REJECTION &gt; INTERVIEW &gt; ASSESSMENT &gt; ACKNOWLEDGEMENT &gt; WITHDRAWAL &gt; UNKNOWN)
 * and best-effort extracts an interview type and a salary token.
 */
public final class EmailClassifier {

    private EmailClassifier() {}

    public record Classification(String category, double confidence) {}

    public record Extraction(String interviewType, String salary) {}

    private static final Pattern SALARY = Pattern.compile(
            "(?:[$€£₹]\\s?\\d[\\d,]*(?:\\.\\d+)?\\s?[kK]?)|(?:\\d[\\d,]*\\s?(?:USD|EUR|GBP|INR|LPA))",
            Pattern.CASE_INSENSITIVE);

    public static Classification classify(String subject, String body) {
        String t = ((subject == null ? "" : subject) + " \n " + (body == null ? "" : body)).toLowerCase();
        if (containsAny(t, "offer letter", "pleased to offer", "job offer", "extend an offer", "compensation package")) {
            return new Classification(ApplicationEmail.CATEGORY_OFFER, 0.9);
        }
        if (containsAny(t, "unfortunately", "not moving forward", "decided not to proceed",
                "regret to inform", "will not be moving", "other candidates", "not selected")) {
            return new Classification(ApplicationEmail.CATEGORY_REJECTION, 0.85);
        }
        if (containsAny(t, "interview", "schedule a call", "meet the team", "technical screen",
                "phone screen", "onsite", "hiring manager")) {
            return new Classification(ApplicationEmail.CATEGORY_INTERVIEW, 0.8);
        }
        if (containsAny(t, "assessment", "coding challenge", "take-home", "take home", "hackerrank",
                "codility", "online test", "aptitude test")) {
            return new Classification(ApplicationEmail.CATEGORY_ASSESSMENT, 0.8);
        }
        if (containsAny(t, "received your application", "thank you for applying", "application received",
                "we have received", "application has been received")) {
            return new Classification(ApplicationEmail.CATEGORY_ACKNOWLEDGEMENT, 0.7);
        }
        if (containsAny(t, "withdraw", "withdrawn", "no longer considered")) {
            return new Classification(ApplicationEmail.CATEGORY_WITHDRAWAL, 0.6);
        }
        return new Classification(ApplicationEmail.CATEGORY_UNKNOWN, 0.0);
    }

    public static Extraction extract(String subject, String body) {
        String t = ((subject == null ? "" : subject) + " \n " + (body == null ? "" : body)).toLowerCase();
        String interviewType = null;
        if (t.contains("system design")) interviewType = "SYSTEM_DESIGN";
        else if (t.contains("technical")) interviewType = "TECHNICAL";
        else if (t.contains("hiring manager") || t.contains("manager")) interviewType = "MANAGER";
        else if (t.contains("hr ") || t.contains("recruiter")) interviewType = "HR";
        else if (t.contains("final")) interviewType = "FINAL";

        String salary = null;
        Matcher m = SALARY.matcher((subject == null ? "" : subject) + " " + (body == null ? "" : body));
        if (m.find()) salary = m.group().trim();

        return new Extraction(interviewType, salary);
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }
}
