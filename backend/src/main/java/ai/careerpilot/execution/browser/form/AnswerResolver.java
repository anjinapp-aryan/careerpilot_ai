package ai.careerpilot.execution.browser.form;

import ai.careerpilot.domain.ApplicationSubmissionAnswer;
import ai.careerpilot.domain.User;
import ai.careerpilot.employerquestion.AnswerResolution;
import ai.careerpilot.employerquestion.EmployerAnswerService;
import ai.careerpilot.repo.ApplicationSubmissionAnswerRepository;
import ai.careerpilot.repo.UserRepository;
import org.springframework.beans.factory.ObjectProvider;
import ai.careerpilot.submission.mapping.FieldMappingResult;
import ai.careerpilot.submission.mapping.FieldMappingService;
import ai.careerpilot.submission.mapping.MappedField;
import ai.careerpilot.submission.question.QuestionCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 12C — resolves a {@link CanonicalField} to a <b>verified</b> value from data this platform
 * already holds. <b>This class is where the phase's "never fabricate" rule is actually enforced.</b>
 *
 * <p>It generates nothing. It calls no AI. Every value it returns came from one of exactly three
 * pre-existing sources, and every one of them is named in {@link ResolvedValue#source()}:
 * <ol>
 *   <li>{@code FieldMappingService} (Phase 7.16) — {@code User} + {@code CandidateProfile} facts,
 *       already audited for data-source honesty.</li>
 *   <li>{@code User} directly, only for the first/last-name split that {@code FieldMappingService}
 *       does not expose separately (it maps {@code fullName} only).</li>
 *   <li>Persisted {@code ApplicationSubmissionAnswer} rows for the session — answers a human can
 *       already see and edit on the approval screen before any of this runs.</li>
 * </ol>
 *
 * <p>{@link QuestionCategory} is the join key between a discovered form label and a stored answer.
 * That is what makes matching work across ATS vendors without per-employer code: Greenhouse's
 * "What are your salary expectations?" and Ashby's "Expected compensation" both classify to
 * {@code SALARY} through the <em>existing</em> {@code QuestionDetectionService}, and both find the
 * same stored answer. Exact and normalised text matching are tried first so a verbatim question
 * always wins over a category sibling.
 *
 * <p>When nothing resolves, the result is an explicit unresolved value carrying a reason. It is
 * never a blank string, a placeholder, a "N/A", or a best guess.
 */
@Service
public class AnswerResolver {

    private static final Logger log = LoggerFactory.getLogger(AnswerResolver.class);

    private final FieldMappingService fieldMapping;
    private final UserRepository users;
    private final ApplicationSubmissionAnswerRepository answers;

    /**
     * Phase E — optional, because Phase D's library has its own independent feature flag and this
     * resolver must construct and behave identically when that subsystem is absent or dark.
     */
    private final ObjectProvider<EmployerAnswerService> employerAnswers;

    /**
     * {@code @Autowired} is load-bearing: with two constructors and none marked, Spring cannot
     * choose and falls back to looking for a no-arg constructor, which fails context startup. That
     * is exactly how this broke on first deploy.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public AnswerResolver(FieldMappingService fieldMapping, UserRepository users,
                          ApplicationSubmissionAnswerRepository answers,
                          ObjectProvider<EmployerAnswerService> employerAnswers) {
        this.fieldMapping = fieldMapping;
        this.users = users;
        this.answers = answers;
        this.employerAnswers = employerAnswers;
    }

    /**
     * Pre-Phase-E compatibility constructor: no employer answer library. Resolution then behaves
     * exactly as it did before this phase.
     */
    public AnswerResolver(FieldMappingService fieldMapping, UserRepository users,
                          ApplicationSubmissionAnswerRepository answers) {
        this(fieldMapping, users, answers, null);
    }

    /**
     * Everything needed to resolve one form, gathered once so a 40-field form does not issue 40
     * repeat queries. Built by {@link #loadContext}.
     */
    public record ResolutionContext(Map<String, MappedField> mappedByName,
                                    String fullName,
                                    List<ApplicationSubmissionAnswer> storedAnswers,
                                    UUID userId,
                                    Map<String, AnswerResolution> employerResolutions) {

        public ResolutionContext {
            employerResolutions = employerResolutions == null ? Map.of() : Map.copyOf(employerResolutions);
        }

        /**
         * P2 compatibility constructor — a context with no pre-resolved employer answers. Kept so
         * every caller that does not supply lookups still compiles; such a context simply finds no
         * library answer, which is the same outcome as the library being disabled.
         */
        public ResolutionContext(Map<String, MappedField> mappedByName, String fullName,
                                 List<ApplicationSubmissionAnswer> storedAnswers, UUID userId) {
            this(mappedByName, fullName, storedAnswers, userId, Map.of());
        }

        /**
         * Phase E compatibility constructor. A context built without a user id cannot consult the
         * employer answer library — that is the correct degradation, since a library answer is
         * owned by a specific candidate and an unattributable lookup must never return one.
         */
        public ResolutionContext(Map<String, MappedField> mappedByName, String fullName,
                                 List<ApplicationSubmissionAnswer> storedAnswers) {
            this(mappedByName, fullName, storedAnswers, null, Map.of());
        }

        public static ResolutionContext empty() {
            return new ResolutionContext(Map.of(), null, List.of(), null, Map.of());
        }
    }

    /**
     * One query per source, up front. {@code sessionId} may be null — a form can be planned without
     * a submission session, in which case screening questions simply have no answers to match and
     * are reported unresolved rather than failing.
     */
    public ResolutionContext loadContext(UUID userId, UUID sessionId) {
        return loadContext(userId, sessionId, List.of());
    }

    /**
     * P2 Work Item 1 — same up-front load, plus every employer-library answer this form needs,
     * fetched in <b>two</b> queries instead of two per field.
     *
     * <p>{@code lookups} is the whole form's set of (question text, canonical field) pairs, known
     * because the planner classifies before it resolves. Passing them here is what lets
     * {@link ai.careerpilot.employerquestion.EmployerAnswerService#resolveAll} load the library once
     * and this candidate's answers once; {@link #fromEmployerLibrary} then becomes a map hit with no
     * I/O at all.
     */
    public ResolutionContext loadContext(UUID userId, UUID sessionId,
                                         List<EmployerAnswerService.Lookup> lookups) {
        Map<String, MappedField> byName = new HashMap<>();
        String fullName = null;
        List<ApplicationSubmissionAnswer> stored = List.of();

        try {
            FieldMappingResult mapping = fieldMapping.map(userId);
            for (MappedField f : mapping.fields()) {
                byName.put(f.fieldName(), f);
            }
        } catch (Exception e) {
            log.warn("FORM_RESOLVER field mapping failed user={}: {}", userId, e.toString());
        }
        try {
            fullName = users.findById(userId).map(User::getFullName).orElse(null);
        } catch (Exception e) {
            log.warn("FORM_RESOLVER user lookup failed user={}: {}", userId, e.toString());
        }
        if (sessionId != null) {
            try {
                stored = answers.findBySessionIdOrderByCreatedAtAsc(sessionId);
            } catch (Exception e) {
                log.warn("FORM_RESOLVER answer lookup failed session={}: {}", sessionId, e.toString());
            }
        }
        // Two queries for the whole form, or none at all when the library is off/absent.
        Map<String, AnswerResolution> employerResolutions = Map.of();
        EmployerAnswerService library = employerAnswers == null ? null : employerAnswers.getIfAvailable();
        if (library != null && library.isEnabled() && userId != null && !lookups.isEmpty()) {
            try {
                employerResolutions = library.resolveAll(userId, lookups);
            } catch (Exception e) {
                // Fail closed: an empty map means every field falls through to profile/stored
                // resolution, which is the same degradation the per-field path already had.
                log.warn("FORM_RESOLVER employer bulk resolve failed user={}: {}", userId, e.toString());
            }
        }

        return new ResolutionContext(Map.copyOf(byName), fullName, List.copyOf(stored), userId,
                Map.copyOf(employerResolutions));
    }

    /**
     * Resolve one field. Never throws, never returns null, never invents.
     *
     * @param questionCategory the category of the field's label, for screening questions
     * @param questionText     the field's own label, for exact/normalised answer matching
     */
    public ResolvedValue resolve(CanonicalField field, ResolutionContext ctx,
                                 QuestionCategory questionCategory, String questionText) {
        if (field == null || ctx == null) return ResolvedValue.unresolved("no field or context");

        // ── Phase E — the employer answer library sits at the TOP of the resolution priority.
        //
        // A human who read this exact question and approved this exact text is the strongest
        // evidence this platform can hold: stronger than a profile column, because the approval was
        // given for this question rather than inferred to apply to it. Answers are keyed by matched
        // question AND canonical field, so an approved COUNTRY answer can only ever satisfy a
        // COUNTRY question — a library hit cannot leak across meanings.
        //
        // Gated by the library's own flag (employer.question.intelligence.enabled), so with Phase D
        // dark this call is a no-op and resolution is byte-for-byte the pre-Phase-E behaviour.
        // A present result is DECISIVE — either an approved answer, or the library recognising the
        // question and declining it. A decline is returned rather than falling through because the
        // library's reason is the actionable one: "a draft is awaiting your approval" tells a human
        // what to do, whereas the downstream "no stored answers for this session" does not, and
        // losing that distinction is how a reviewable item becomes invisible. An empty result means
        // the library did not recognise the question at all, so normal resolution proceeds.
        Optional<ResolvedValue> fromLibrary = fromEmployerLibrary(ctx, field, questionText);
        if (fromLibrary.isPresent()) return fromLibrary.get();

        // A field this platform structurally cannot answer. Reported as a product gap, and
        // deliberately checked before anything else so it can never be accidentally satisfied by a
        // loosely-matching stored answer.
        if (field.hasNoDataSource()) {
            return ResolvedValue.noDataSource(field);
        }

        return switch (field) {
            case FIRST_NAME -> nameToken(ctx, true);
            case LAST_NAME -> nameToken(ctx, false);
            case FULL_NAME -> fromMapping(ctx, "fullName");
            case EMAIL -> fromMapping(ctx, "email");
            case YEARS_EXPERIENCE -> fromMapping(ctx, "yearsExperience");
            case SKILLS -> fromMapping(ctx, "skills");

            // ── Phase C ──
            // Every arm below is a plain lookup against a field name FieldMappingService already
            // produced. No new source, no new query, and no generation: this resolver still calls no
            // AI and invents nothing. Provenance was already enforced upstream — the mapper only
            // emits values whose recorded source is trusted for automation — so an unreviewed
            // suggestion arrives here as unmapped and is reported unresolved.
            case PHONE -> fromMapping(ctx, "phone");
            case LINKEDIN_URL -> fromMapping(ctx, "linkedinUrl");
            case GITHUB_URL -> fromMapping(ctx, "githubUrl");
            case PORTFOLIO_URL -> fromMapping(ctx, "portfolioUrl");
            case PERSONAL_WEBSITE -> fromMapping(ctx, "personalWebsiteUrl");

            // The candidate's own ATS profile wins over the home-country preference: an employer
            // asking for a country of residence wants a present fact, not a job-search setting.
            case COUNTRY -> firstResolved(fromMapping(ctx, "country"), fromMapping(ctx, "location"));
            case CITY -> fromMapping(ctx, "city");
            case STATE -> fromMapping(ctx, "stateProvince");
            case ADDRESS -> fromMapping(ctx, "addressLine1");
            case POSTAL_CODE -> fromMapping(ctx, "postalCode");

            case CURRENT_COMPANY -> fromMapping(ctx, "currentCompany");
            case CURRENT_TITLE -> fromMapping(ctx, "currentTitle");
            case CURRENT_SALARY -> fromMapping(ctx, "currentSalary");

            case HIGHEST_EDUCATION -> fromMapping(ctx, "highestEducation");
            case DEGREE -> fromMapping(ctx, "degree");
            case FIELD_OF_STUDY -> fromMapping(ctx, "fieldOfStudy");
            case UNIVERSITY -> fromMapping(ctx, "university");
            case GRADUATION_YEAR -> fromMapping(ctx, "graduationYear");

            case WORK_AUTHORIZATION -> fromMapping(ctx, "workAuthorization");
            case VISA_STATUS -> fromMapping(ctx, "visaStatus");
            case CITIZENSHIP -> fromMapping(ctx, "citizenship");
            case SECURITY_CLEARANCE -> fromMapping(ctx, "securityClearance");

            case LANGUAGES -> fromMapping(ctx, "languages");
            case CERTIFICATIONS -> fromMapping(ctx, "certifications");

            // Salary/visa have BOTH a profile column and a possible stored answer. The profile
            // column wins: it is a structured fact the user set directly, whereas the stored answer
            // is AI-drafted prose that may not fit a numeric or yes/no input.
            case SALARY_EXPECTATION -> firstResolved(
                    fromMapping(ctx, "salaryTarget"),
                    fromStoredAnswer(ctx, QuestionCategory.SALARY, questionText));
            case VISA_SPONSORSHIP -> firstResolved(
                    fromMapping(ctx, "visaRequired"),
                    fromStoredAnswer(ctx, QuestionCategory.VISA, questionText));

            // Phase C gave notice period a real profile column. It now follows the same precedence
            // as salary and visa above: a structured fact the candidate set directly beats
            // AI-drafted prose, which may not fit a short or numeric input.
            case NOTICE_PERIOD -> firstResolved(
                    fromMapping(ctx, "noticePeriod"),
                    fromStoredAnswer(ctx, QuestionCategory.NOTICE_PERIOD, questionText));
            case RELOCATION -> fromStoredAnswer(ctx, QuestionCategory.RELOCATION, questionText);
            case REMOTE_PREFERENCE -> fromStoredAnswer(ctx, QuestionCategory.REMOTE_PREFERENCE, questionText);

            case SCREENING_QUESTION -> fromStoredAnswer(ctx, questionCategory, questionText);

            // Files carry a path, not a string — resolved by the engine from ApplicationPackage,
            // not from here. Saying so explicitly beats returning a bare "unresolved".
            case RESUME_UPLOAD, COVER_LETTER_UPLOAD ->
                    ResolvedValue.unresolved("file uploads are resolved from the ApplicationPackage, not the value resolver");
            case COVER_LETTER_TEXT ->
                    ResolvedValue.unresolved("cover letter text is resolved from the ApplicationPackage, not the value resolver");

            case UNKNOWN -> ResolvedValue.unresolved("field could not be identified");
            default -> ResolvedValue.unresolved("no resolution rule for " + field);
        };
    }

    // ── sources ──

    /**
     * Phase E — a human-approved answer from the Phase D library, if one exists for this question.
     *
     * <p>Returns unresolved in every degraded case rather than throwing: no library bean, feature
     * off, no user id, no question text, no match, or a match whose answer is still a draft. The
     * library's own {@code resolve} already refuses anything unapproved or carrying an untrusted
     * confidence band, so this method never has to re-decide trust — it only has to not fabricate
     * when the library declines.
     */
    private Optional<ResolvedValue> fromEmployerLibrary(ResolutionContext ctx, CanonicalField field,
                                                        String questionText) {
        if (ctx.userId() == null || questionText == null || questionText.isBlank()) {
            // An unattributable lookup must never return a candidate's stored answer. Not decisive:
            // normal resolution still applies.
            return Optional.empty();
        }
        try {
            // P2 Work Item 1 — a map hit, not a query. loadContext resolved the entire form in two
            // queries before this loop began; there is deliberately no repository access here, and
            // no bean lookup either, so nothing in the per-field path can reach the database.
            AnswerResolution resolution = ctx.employerResolutions()
                    .get(new EmployerAnswerService.Lookup(questionText, field.name()).key());
            if (resolution == null) return Optional.empty();
            if (resolution.usable()) {
                return Optional.of(ResolvedValue.of(resolution.answerText(),
                        "EmployerAnswer[" + resolution.confidence() + "] approved "
                                + resolution.approvedAt()));
            }
            // Recognised but declined — a draft awaiting approval, or an approved answer whose
            // confidence band is not usable. Decisive: surfacing this reason is what makes the item
            // reviewable instead of silently indistinguishable from "no data".
            if (resolution.questionId() != null) {
                return Optional.of(ResolvedValue.unresolved(resolution.reason()));
            }
            // Not recognised at all — fall through to profile and stored-answer resolution.
            return Optional.empty();
        } catch (Exception e) {
            // Fail closed AND decisively: a library failure must not be papered over by a
            // downstream source, because we cannot know whether an approved answer existed.
            log.warn("FORM_RESOLVER employer answer lookup failed field={}: {}", field, e.toString());
            return Optional.of(ResolvedValue.unresolved("employer answer lookup failed"));
        }
    }

    private ResolvedValue fromMapping(ResolutionContext ctx, String mappedFieldName) {
        MappedField f = ctx.mappedByName().get(mappedFieldName);
        if (f == null) {
            return ResolvedValue.unresolved("field mapping produced no entry for " + mappedFieldName);
        }
        if (f.unmapped() || f.value() == null || f.value().isBlank()) {
            return ResolvedValue.unresolved("no value set for " + mappedFieldName + " (source: " + f.source() + ")");
        }
        return ResolvedValue.of(f.value(), f.source());
    }

    /**
     * {@code FieldMappingService} exposes {@code fullName} only, so the first/last split is done
     * here — the same tokenisation {@code GuestApplyAutomationService} already uses, kept identical
     * so the two paths cannot disagree about a candidate's name.
     */
    private ResolvedValue nameToken(ResolutionContext ctx, boolean first) {
        String fullName = ctx.fullName();
        if (fullName == null || fullName.isBlank()) {
            ResolvedValue mapped = fromMapping(ctx, "fullName");
            if (!mapped.isResolved()) return mapped;
            fullName = mapped.value();
        }
        String[] parts = fullName.trim().split("\\s+");
        if (first) {
            return ResolvedValue.of(parts[0], "User.fullName[first token]");
        }
        if (parts.length < 2) {
            // A single-token name has no surname. Repeating the first name into a "Last name"
            // field would be fabricating a surname the user never gave.
            return ResolvedValue.unresolved("user's name has no surname token");
        }
        return ResolvedValue.of(parts[parts.length - 1], "User.fullName[last token]");
    }

    /**
     * Match a stored answer. Three tiers, most precise first — exact question text, normalised
     * text, then category. Only the first tier can be considered certain; the category tier is what
     * makes the engine portable across ATS wordings, and is why {@code QuestionCategory} exists.
     */
    private ResolvedValue fromStoredAnswer(ResolutionContext ctx, QuestionCategory category, String questionText) {
        List<ApplicationSubmissionAnswer> stored = ctx.storedAnswers();
        if (stored.isEmpty()) {
            return ResolvedValue.unresolved("no verified answer available (no stored answers for this session)");
        }

        if (questionText != null && !questionText.isBlank()) {
            String target = normalise(questionText);
            Optional<ApplicationSubmissionAnswer> exact = stored.stream()
                    .filter(a -> questionText.equalsIgnoreCase(safe(a.getQuestionText())))
                    .findFirst();
            if (exact.isPresent()) return answerOf(exact.get(), "exact question match");

            Optional<ApplicationSubmissionAnswer> normalised = stored.stream()
                    .filter(a -> target.equals(normalise(safe(a.getQuestionText()))))
                    .findFirst();
            if (normalised.isPresent()) return answerOf(normalised.get(), "normalised question match");
        }

        if (category != null && category != QuestionCategory.OTHER) {
            Optional<ApplicationSubmissionAnswer> byCategory = stored.stream()
                    .filter(a -> category.name().equalsIgnoreCase(safe(a.getQuestionCategory())))
                    .findFirst();
            if (byCategory.isPresent()) return answerOf(byCategory.get(), "category match [" + category + "]");
        }

        return ResolvedValue.unresolved("No verified answer available.");
    }

    private static ResolvedValue answerOf(ApplicationSubmissionAnswer a, String how) {
        String text = a.getAnswerText();
        if (text == null || text.isBlank()) {
            return ResolvedValue.unresolved("stored answer exists but is empty (" + how + ")");
        }
        // AnswerGenerationService returns this exact sentinel when the AI call failed. Treating it
        // as a real answer would type an apology into a live employer's form.
        if (text.startsWith("Unable to generate an answer at this time")) {
            return ResolvedValue.unresolved("stored answer is the generation-failure placeholder");
        }
        return ResolvedValue.of(text, "ApplicationSubmissionAnswer[" + a.getQuestionCategory() + "] via " + how);
    }

    /** Lowercase, strip punctuation and collapse whitespace — tolerant of trailing "?" and "*". */
    private static String normalise(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static ResolvedValue firstResolved(ResolvedValue... candidates) {
        ResolvedValue last = ResolvedValue.unresolved("no candidate sources");
        for (ResolvedValue c : candidates) {
            if (c.isResolved()) return c;
            last = c;
        }
        return last;
    }
}
