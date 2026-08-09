package ai.careerpilot.submission;

import ai.careerpilot.domain.CandidateProfile;
import ai.careerpilot.domain.Resume;
import ai.careerpilot.domain.User;
import ai.careerpilot.execution.browser.form.AnswerResolver;
import ai.careerpilot.execution.browser.form.CanonicalField;
import ai.careerpilot.execution.browser.form.ResolvedValue;
import ai.careerpilot.repo.CandidateProfileRepository;
import ai.careerpilot.repo.ResumeRepository;
import ai.careerpilot.repo.UserRepository;
import ai.careerpilot.service.profile.JsonLists;
import ai.careerpilot.submission.mapping.FieldMappingService;
import ai.careerpilot.submission.question.QuestionCategory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Guided Apply — the "CareerPilot prepares" half of {@code CareerPilot prepares. User completes.}
 *
 * <p>Deliberately not a new resolution engine: every value here comes from {@link AnswerResolver}
 * (Action 5C-FIX's own answer-resolution seam — the exact class the browser form engine already
 * uses and already proved never fabricates) and {@link FieldMappingService}. This class only
 * projects their output into a small, job-independent-of-DOM shape for a human to read, never
 * resolves anything itself.
 *
 * <p><b>Never fabricates.</b> An unresolved {@link CanonicalField} is reported with {@code
 * needsUserInput=true} and a null value — never a guess, never a plausible-looking default.
 */
@Service
public class GuidedApplyBriefService {

    private final UserRepository users;
    private final CandidateProfileRepository profiles;
    private final ResumeRepository resumes;
    private final AnswerResolver answerResolver;

    public GuidedApplyBriefService(UserRepository users, CandidateProfileRepository profiles,
                                   ResumeRepository resumes, AnswerResolver answerResolver) {
        this.users = users;
        this.profiles = profiles;
        this.resumes = resumes;
        this.answerResolver = answerResolver;
    }

    /** One resolvable application-profile fact, shown in the "Application Profile" section. */
    public record ProfileFact(String label, String value, String source) {}

    /**
     * One predictable application question, resolved (or honestly not) against verified data.
     * {@code confidence} is null exactly when {@code needsUserInput} is true — an unresolved answer
     * has no confidence to report.
     */
    public record RecommendedAnswer(String question, String canonicalField, String value,
                                    String source, String confidence, boolean needsUserInput) {}

    public record GuidedApplyBrief(String candidateName, String candidateEmail, String resumeFilename,
                                   List<ProfileFact> profile, List<RecommendedAnswer> recommendedAnswers) {}

    /**
     * @param resumeId the application's own resume, if one was attached — may be null (Scenario E,
     *                 "Resume required before Guided Apply", is a caller-side check against this)
     */
    public GuidedApplyBrief buildFor(UUID userId, UUID resumeId) {
        User user = users.findById(userId).orElse(null);
        CandidateProfile profile = profiles.findByUserId(userId).orElse(null);
        // Guided Apply Hardening — findByIdAndUserId, not a bare findById. The controller's own
        // Application ownership check (ApplicationService#getOwned) already makes a cross-user
        // resumeId unreachable in practice today, but this method has no other signal of its own
        // that resumeId belongs to userId — a defense-in-depth check costs one WHERE clause and
        // closes that gap regardless of how future callers are wired.
        String resumeFilename = resumeId == null ? null
                : resumes.findByIdAndUserId(resumeId, userId).map(Resume::getFilename).orElse(null);

        AnswerResolver.ResolutionContext ctx = answerResolver.loadContext(userId, null);

        List<RecommendedAnswer> answers = new ArrayList<>();
        answers.add(resolve(ctx, CanonicalField.PHONE, "Phone number"));
        answers.add(resolve(ctx, CanonicalField.YEARS_EXPERIENCE, "Years of relevant experience"));
        answers.add(resolve(ctx, CanonicalField.WORK_AUTHORIZATION,
                "Are you legally authorized to work in this country?"));
        answers.add(resolve(ctx, CanonicalField.VISA_SPONSORSHIP,
                "Will you now or in the future require sponsorship for employment visa status?"));
        answers.add(resolve(ctx, CanonicalField.NOTICE_PERIOD, "What is your notice period?"));
        answers.add(resolve(ctx, CanonicalField.CURRENT_SALARY, "What is your current salary?"));

        Map<String, RecommendedAnswer> byField = new LinkedHashMap<>();
        for (RecommendedAnswer a : answers) byField.put(a.canonicalField(), a);

        List<ProfileFact> profileFacts = new ArrayList<>();
        if (user != null && user.getFullName() != null) {
            profileFacts.add(new ProfileFact("Name", user.getFullName(), "User.fullName"));
        }
        if (user != null && user.getEmail() != null) {
            profileFacts.add(new ProfileFact("Email", user.getEmail(), "User.email"));
        }
        addIfResolved(profileFacts, byField, CanonicalField.PHONE.name(), "Phone");
        addIfResolved(profileFacts, byField, CanonicalField.YEARS_EXPERIENCE.name(), "Experience");
        if (profile != null && profile.getSkillsJson() != null && !profile.getSkillsJson().isBlank()) {
            profileFacts.add(new ProfileFact("Primary Skills",
                    String.join(", ", JsonLists.toList(profile.getSkillsJson())), "CandidateProfile.skills"));
        }
        if (resumeFilename != null) {
            profileFacts.add(new ProfileFact("Resume", resumeFilename, "Resume.filename"));
        }
        addIfResolved(profileFacts, byField, CanonicalField.WORK_AUTHORIZATION.name(), "Work Authorization");
        addIfResolved(profileFacts, byField, CanonicalField.VISA_SPONSORSHIP.name(), "Sponsorship Required");
        addIfResolved(profileFacts, byField, CanonicalField.NOTICE_PERIOD.name(), "Notice Period");

        return new GuidedApplyBrief(
                user == null ? null : user.getFullName(),
                user == null ? null : user.getEmail(),
                resumeFilename, profileFacts, answers);
    }

    private static void addIfResolved(List<ProfileFact> out, Map<String, RecommendedAnswer> byField,
                                       String field, String label) {
        RecommendedAnswer a = byField.get(field);
        if (a != null && !a.needsUserInput()) out.add(new ProfileFact(label, a.value(), a.source()));
    }

    private RecommendedAnswer resolve(AnswerResolver.ResolutionContext ctx, CanonicalField field, String questionText) {
        ResolvedValue value = answerResolver.resolve(field, ctx, QuestionCategory.OTHER, questionText);
        if (!value.isResolved()) {
            return new RecommendedAnswer(questionText, field.name(), null, null, null, true);
        }
        return new RecommendedAnswer(questionText, field.name(), value.value(), value.source(),
                confidenceOf(value.source()), false);
    }

    /**
     * A structured-profile-column source (CandidateProfile/CandidateAtsProfile/User) is HIGH — the
     * candidate set it directly. A stored/generated answer is MEDIUM — real, but AI-assisted prose
     * rather than a structured fact. Never HIGH for anything this class cannot trace to a real column.
     */
    private static String confidenceOf(String source) {
        if (source == null) return "LOW";
        String s = source.toLowerCase(Locale.ROOT);
        if (s.startsWith("candidateprofile.") || s.startsWith("candidateatsprofile.") || s.startsWith("user.")) {
            return "HIGH";
        }
        if (s.contains("applicationsubmissionanswer") || s.contains("employeranswer")) {
            return "MEDIUM";
        }
        return "LOW";
    }
}
