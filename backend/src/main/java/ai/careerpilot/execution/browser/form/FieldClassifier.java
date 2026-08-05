package ai.careerpilot.execution.browser.form;

import ai.careerpilot.submission.question.QuestionCategory;
import ai.careerpilot.submission.question.QuestionDetectionService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Phase 12C — maps a {@link DiscoveredField} to what it means, deterministically. No LLM, matching
 * the discipline of every other classifier in this codebase ({@code QuestionDetectionService},
 * {@code CopilotSkillRouter#inferSkillFromMessage}, {@code RetryPolicyService}).
 *
 * <p><b>Ordering is the whole design.</b> Rules run most-specific first, because ATS label text
 * overlaps heavily:
 * <ul>
 *   <li>{@code "Cover letter"} must beat the generic file-upload rule, or a cover letter is
 *       uploaded into the resume slot.</li>
 *   <li>{@code "First name"} must beat {@code "name"}, or every name field becomes FULL_NAME.</li>
 *   <li>{@code "LinkedIn profile URL"} must beat the generic URL rule.</li>
 * </ul>
 *
 * <p>Whole-word matching is used for short, collision-prone tokens (the same
 * {@code Pattern.compile("\\b…\\b")} idiom as {@code RoleExclusionFilter}), because a substring
 * check for {@code "cv"} matches "Have you received a COVID vaccine", and one for {@code "name"}
 * matches "username". Longer distinctive phrases use plain substring matching, which is both
 * cheaper and more tolerant of surrounding punctuation.
 *
 * <p>Anything unrecognised falls through to a screening-question check and finally to
 * {@link CanonicalField#UNKNOWN} — <b>never a guess</b>. An unknown field is left blank and
 * reported, which is the behaviour the phase's truthfulness rules require.
 */
@Component
public class FieldClassifier {

    private final QuestionDetectionService questionDetection;

    public FieldClassifier(QuestionDetectionService questionDetection) {
        this.questionDetection = questionDetection;
    }

    private static final Pattern CV = word("cv");
    private static final Pattern RESUME = word("resume|résumé");
    private static final Pattern FIRST = word("first|given|forename");
    private static final Pattern LAST = word("last|surname|family");
    private static final Pattern NAME = word("name");
    private static final Pattern EMAIL = word("email|e-mail");
    private static final Pattern PHONE = word("phone|mobile|telephone|contact number");

    /**
     * Classify one field. Never throws; never returns null.
     */
    public CanonicalField classify(DiscoveredField field) {
        if (field == null) return CanonicalField.UNKNOWN;
        String text = field.identityText();
        FieldControlType type = field.controlType();

        // ── 1. File uploads. Checked first and by control type, because on a file input the label
        // is the only thing distinguishing a resume slot from a cover-letter slot, and getting it
        // wrong uploads the wrong document to a real employer. ──
        if (type == FieldControlType.FILE) {
            if (containsAny(text, "cover letter", "coverletter", "cover_letter", "covering letter")) {
                return CanonicalField.COVER_LETTER_UPLOAD;
            }
            if (RESUME.matcher(text).find() || CV.matcher(text).find()) {
                return CanonicalField.RESUME_UPLOAD;
            }
            // An unlabelled file input is NOT assumed to be the resume. Guessing here is exactly
            // the failure mode that would attach the wrong document to a real application.
            return CanonicalField.UNKNOWN;
        }

        // ── 2. Cover letter as text (textarea / rich text). ──
        if (containsAny(text, "cover letter", "coverletter", "cover_letter", "covering letter")) {
            return CanonicalField.COVER_LETTER_TEXT;
        }

        // ── 3. Identity. Specific before general. ──
        if (autocompleteIs(field, "given-name") || (FIRST.matcher(text).find() && NAME.matcher(text).find())) {
            return CanonicalField.FIRST_NAME;
        }
        if (autocompleteIs(field, "family-name") || (LAST.matcher(text).find() && NAME.matcher(text).find())) {
            return CanonicalField.LAST_NAME;
        }
        if (autocompleteIs(field, "email") || EMAIL.matcher(text).find() || type == FieldControlType.EMAIL) {
            return CanonicalField.EMAIL;
        }
        if (autocompleteIs(field, "tel") || PHONE.matcher(text).find() || type == FieldControlType.TEL) {
            return CanonicalField.PHONE;
        }
        if (autocompleteIs(field, "name") || NAME.matcher(text).find()) {
            return CanonicalField.FULL_NAME;
        }

        // ── 4. Profile links. Each is checked by its own host token; a bare "website"/"url" field
        // is deliberately NOT claimed as a portfolio, since this platform has no portfolio field to
        // fill it from anyway. ──
        if (text.contains("linkedin")) return CanonicalField.LINKEDIN_URL;
        if (text.contains("github")) return CanonicalField.GITHUB_URL;
        if (containsAny(text, "portfolio", "personal website", "personal site")) return CanonicalField.PORTFOLIO_URL;

        // ── 5. Structured profile facts. These map onto real CandidateProfile columns. ──
        if (containsAny(text, "years of experience", "years experience", "yrs experience",
                "total experience", "experience in years")) {
            return CanonicalField.YEARS_EXPERIENCE;
        }
        if (containsAny(text, "skills", "technologies", "tech stack")) {
            return CanonicalField.SKILLS;
        }

        // ── 5b. Phase D — employer questions that map onto a real CandidateAtsProfile column.
        //
        // ORDERING IS LOAD-BEARING: these run BEFORE the QuestionCategory delegation below because
        // that classifier maps "work authorization" and "authorized to work" onto VISA, and any
        // "salary" onto SALARY. Both are right for the questions it was built for and wrong here —
        // "Are you legally authorised to work in Germany?" is a statement of present legal fact,
        // not a sponsorship preference, and "What is your current salary?" is not an expectation.
        // Running after the delegation would silently answer those with the wrong data.
        //
        // Matching is semantic-by-vocabulary, never by employer: no rule below names a company or
        // an ATS, so a question phrased any of a dozen ways lands on the same canonical field.

        // Work authorisation before anything containing "visa": a sponsorship question has no
        // authorisation vocabulary in it, so "Do you require visa sponsorship?" still reaches the
        // delegation and stays VISA_SPONSORSHIP.
        if (containsAny(text, "authorized to work", "authorised to work", "work authorization",
                "work authorisation", "right to work", "work permit", "legally authorized",
                "legally authorised", "eligible to work", "permitted to work")) {
            return CanonicalField.WORK_AUTHORIZATION;
        }
        if (containsAny(text, "citizenship", "citizen of", "nationality", "country of citizenship")) {
            return CanonicalField.CITIZENSHIP;
        }
        if (containsAny(text, "security clearance", "clearance level", "government clearance")) {
            return CanonicalField.SECURITY_CLEARANCE;
        }

        // Current salary must beat the generic salary rule, which means expectation.
        if (containsAny(text, "current salary", "current compensation", "present salary",
                "current ctc", "present ctc", "existing salary")) {
            return CanonicalField.CURRENT_SALARY;
        }

        // ── 6. The five questions that are BOTH common form fields and QuestionCategory values.
        // Delegating to the existing classifier keeps one definition of "this is a salary question"
        // instead of a second, drifting copy. ──
        QuestionCategory category = questionDetection.classify(text);
        CanonicalField fromCategory = fromCategory(category);
        if (fromCategory != null) return fromCategory;

        // ── 6b. Phase D — value-request fields (location, employment, education).
        //
        // These run AFTER the category delegation, and only for labels that are asking for a VALUE
        // rather than posing a yes/no screening question. Both conditions were learned from real
        // misclassifications on a live posting:
        //
        //   "Will you now or in the future require sponsorship for a visa to remain in your
        //    current location?"            -> ran before the delegation, matched "current
        //                                     location", and became CITY instead of VISA.
        //   "Are you subject to any employment agreements and/or post-employment restrictions
        //    with your current employer?"  -> matched "current employer" and became
        //                                     CURRENT_COMPANY, which would have typed the
        //                                     candidate's employer name into a yes/no question.
        //
        // Both were marked resolvable, so both would have been filled with confidently wrong data.
        // A yes/no opener means the label is a screening question that merely mentions a field's
        // vocabulary; a "what/which/where" opener, or no opener at all, means it wants the value.
        if (!looksLikeYesNoQuestion(text)) {
            CanonicalField valueField = classifyValueRequest(text);
            if (valueField != null) return valueField;
        }

        // ── 7. Free-text screening question. Only textual controls qualify: a checkbox whose label
        // classified as OTHER is not a "question we have an answer for", it is an unknown control. ──
        if (type.isTextual() && looksLikeAQuestion(field)) {
            return CanonicalField.SCREENING_QUESTION;
        }

        return CanonicalField.UNKNOWN;
    }

    /**
     * A label posing a yes/no screening question rather than requesting a value.
     *
     * <p>The openers are auxiliaries — "are you", "have you", "will you" — which is what makes a
     * label a question <em>about</em> the candidate rather than a request <em>for</em> a fact. A
     * "what"/"which"/"where" opener is deliberately absent: "What is your current country of
     * residence?" is interrogative but wants a value, and must still classify as COUNTRY.
     */
    private static boolean looksLikeYesNoQuestion(String text) {
        String t = text.trim();
        return startsWithAny(t, "are you", "have you", "will you", "do you", "did you", "does your",
                "can you", "could you", "would you", "were you", "is there", "has any", "if you",
                "are there", "do any", "has your", "had you", "must you", "should you");
    }

    /**
     * Location, employment and education fields. Ordering within is most-specific-first: country
     * before the generic location vocabulary, so "current country of residence" is a country and
     * not a city; university and graduation year before the broad degree rule.
     *
     * @return the matched field, or {@code null} when nothing matches — never a guess
     */
    private static CanonicalField classifyValueRequest(String text) {
        if (containsAny(text, "country of residence", "which country", "what country", "country you",
                "residing country") || isWord(text, "country")) {
            return CanonicalField.COUNTRY;
        }
        if (containsAny(text, "state/province", "state or province", "province", "prefecture")
                || (isWord(text, "state") && text.length() <= 24)) {
            return CanonicalField.STATE;
        }
        if (containsAny(text, "postal code", "postcode", "zip code", "pin code") || isWord(text, "zip")) {
            return CanonicalField.POSTAL_CODE;
        }
        if (containsAny(text, "current location", "where are you located", "where do you live",
                "current city", "city of residence") || isWord(text, "city") || isWord(text, "town")) {
            return CanonicalField.CITY;
        }
        if (containsAny(text, "street address", "address line", "mailing address", "home address")
                || isWord(text, "address")) {
            return CanonicalField.ADDRESS;
        }
        if (containsAny(text, "current company", "current employer", "present employer",
                "present company", "who do you currently work for", "current organisation",
                "current organization")) {
            return CanonicalField.CURRENT_COMPANY;
        }
        if (containsAny(text, "current title", "current job title", "current role", "current position",
                "present designation", "current designation")) {
            return CanonicalField.CURRENT_TITLE;
        }
        if (containsAny(text, "university", "college", "institution attended", "alma mater",
                "school attended")) {
            return CanonicalField.UNIVERSITY;
        }
        if (containsAny(text, "field of study", "major", "specialisation", "specialization",
                "discipline")) {
            return CanonicalField.FIELD_OF_STUDY;
        }
        if (containsAny(text, "graduation year", "year of graduation", "year graduated",
                "completion year")) {
            return CanonicalField.GRADUATION_YEAR;
        }
        if (containsAny(text, "highest education", "highest qualification", "education level",
                "level of education")) {
            return CanonicalField.HIGHEST_EDUCATION;
        }
        if (containsAny(text, "degree", "qualification")) {
            return CanonicalField.DEGREE;
        }
        if (containsAny(text, "languages you speak", "language proficiency", "spoken languages")
                || isWord(text, "languages")) {
            return CanonicalField.LANGUAGES;
        }
        if (containsAny(text, "certification", "certifications", "credentials held")) {
            return CanonicalField.CERTIFICATIONS;
        }
        if (containsAny(text, "personal website", "personal site", "your website", "blog url")) {
            return CanonicalField.PERSONAL_WEBSITE;
        }
        return null;
    }

    /** The subset of {@link QuestionCategory} that also exists as a discrete form field. */
    private static CanonicalField fromCategory(QuestionCategory category) {
        if (category == null) return null;
        return switch (category) {
            case SALARY -> CanonicalField.SALARY_EXPECTATION;
            case NOTICE_PERIOD -> CanonicalField.NOTICE_PERIOD;
            case VISA -> CanonicalField.VISA_SPONSORSHIP;
            case RELOCATION -> CanonicalField.RELOCATION;
            case REMOTE_PREFERENCE -> CanonicalField.REMOTE_PREFERENCE;
            // The behavioural categories (WHY_ROLE, LEADERSHIP, …) are long-form questions, handled
            // by the SCREENING_QUESTION path so they resolve against a stored answer rather than a
            // profile column.
            default -> null;
        };
    }

    /**
     * A textual control is treated as a screening question when its own label reads like one — a
     * question mark, an interrogative opener, or simply a long label. Short labels are excluded
     * because a 2-word label is a field name ("City"), not a question, and treating it as one would
     * paste a paragraph-length answer into it.
     */
    private boolean looksLikeAQuestion(DiscoveredField field) {
        String label = field.label();
        if (label == null || label.isBlank()) label = field.ariaLabel();
        if (label == null || label.isBlank()) label = field.placeholder();
        if (label == null || label.isBlank()) return false;
        String l = label.trim().toLowerCase(Locale.ROOT);
        if (l.contains("?")) return true;
        if (startsWithAny(l, "why ", "what ", "how ", "describe ", "tell us", "tell me",
                "do you", "are you", "have you", "would you", "can you", "please describe",
                "please tell", "explain ")) {
            return true;
        }
        // A long label on a textarea is a question in practice even without a question mark.
        return field.controlType() == FieldControlType.TEXTAREA && l.length() >= 25;
    }

    /**
     * Classify the question text of a screening field into a {@link QuestionCategory}, which is the
     * join key against persisted {@code ApplicationSubmissionAnswer} rows.
     */
    public QuestionCategory questionCategoryOf(DiscoveredField field) {
        if (field == null) return QuestionCategory.OTHER;
        return questionDetection.classify(field.displayName());
    }

    private static boolean autocompleteIs(DiscoveredField field, String token) {
        String ac = field.autocomplete();
        if (ac == null || ac.isBlank()) return false;
        // autocomplete can be a token list ("shipping given-name"); match any token.
        for (String part : ac.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (part.equals(token)) return true;
        }
        return false;
    }

    /**
     * Whole-word match, cached per token. Used for the short, collision-prone location words —
     * a substring check for {@code "city"} matches "capacity" and one for {@code "state"} matches
     * "statement", either of which would file a real question under the wrong canonical field.
     */
    private static boolean isWord(String haystack, String token) {
        return WORD_PATTERNS.computeIfAbsent(token, FieldClassifier::word).matcher(haystack).find();
    }

    private static final java.util.Map<String, Pattern> WORD_PATTERNS = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }

    private static boolean startsWithAny(String s, String... prefixes) {
        for (String p : prefixes) {
            if (s.startsWith(p)) return true;
        }
        return false;
    }

    private static Pattern word(String alternatives) {
        return Pattern.compile("\\b(" + alternatives + ")\\b", Pattern.CASE_INSENSITIVE);
    }

    /** Convenience for callers that classify a whole discovered form at once. */
    public List<CanonicalField> classifyAll(List<DiscoveredField> fields) {
        return fields == null ? List.of() : fields.stream().map(this::classify).toList();
    }
}
