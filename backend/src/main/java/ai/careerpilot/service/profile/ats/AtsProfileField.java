package ai.careerpilot.service.profile.ats;

import ai.careerpilot.domain.CandidateAtsProfile;
import ai.careerpilot.service.profile.JsonLists;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Phase C — the canonical catalogue of ATS profile fields, defined exactly once.
 *
 * <p>Provenance tracking, completeness scoring, and field mapping all need the same three facts
 * about each field: what it is called, how important it is to a real application, and how to read
 * it off the entity. Defining that in three places is how the three quietly stop agreeing — a field
 * added to the entity but forgotten in the completeness engine inflates the score, which is the
 * exact class of dishonesty this phase exists to remove. Everything derives from this enum.
 *
 * <p>The {@link Importance} bands come from what the major ATSes actually enforce. Greenhouse,
 * Lever, Ashby, SmartRecruiters, Workday, Taleo and SuccessFactors all ask for name, email, phone
 * and a résumé; phone is the only one of those owned by this table, so it is the sole
 * {@link Importance#REQUIRED} contact field. Location and links are near-universal but usually
 * optional, and Workday/Taleo/SuccessFactors additionally ask for structured employment, education
 * and work-authorisation detail — which is why those exist here at all.
 */
public enum AtsProfileField {

    // ── Contact ──
    PHONE("phone", Importance.REQUIRED, CandidateAtsProfile::getPhone),
    COUNTRY("country", Importance.REQUIRED, CandidateAtsProfile::getCountry),
    CITY("city", Importance.RECOMMENDED, CandidateAtsProfile::getCity),
    STATE_PROVINCE("stateProvince", Importance.OPTIONAL, CandidateAtsProfile::getStateProvince),
    ADDRESS_LINE1("addressLine1", Importance.OPTIONAL, CandidateAtsProfile::getAddressLine1),
    ADDRESS_LINE2("addressLine2", Importance.OPTIONAL, CandidateAtsProfile::getAddressLine2),
    POSTAL_CODE("postalCode", Importance.OPTIONAL, CandidateAtsProfile::getPostalCode),

    // ── Professional links ──
    LINKEDIN_URL("linkedinUrl", Importance.RECOMMENDED, CandidateAtsProfile::getLinkedinUrl),
    GITHUB_URL("githubUrl", Importance.RECOMMENDED, CandidateAtsProfile::getGithubUrl),
    PORTFOLIO_URL("portfolioUrl", Importance.OPTIONAL, CandidateAtsProfile::getPortfolioUrl),
    PERSONAL_WEBSITE_URL("personalWebsiteUrl", Importance.OPTIONAL, CandidateAtsProfile::getPersonalWebsiteUrl),

    // ── Current employment ──
    CURRENT_COMPANY("currentCompany", Importance.RECOMMENDED, CandidateAtsProfile::getCurrentCompany),
    CURRENT_TITLE("currentTitle", Importance.RECOMMENDED, CandidateAtsProfile::getCurrentTitle),
    NOTICE_PERIOD("noticePeriod", Importance.RECOMMENDED, CandidateAtsProfile::getNoticePeriod),
    CURRENT_SALARY("currentSalary", Importance.OPTIONAL, p -> decimal(p.getCurrentSalary())),
    CURRENT_SALARY_CURRENCY("currentSalaryCurrency", Importance.OPTIONAL, CandidateAtsProfile::getCurrentSalaryCurrency),

    // ── Education ──
    HIGHEST_EDUCATION("highestEducation", Importance.RECOMMENDED, CandidateAtsProfile::getHighestEducation),
    DEGREE("degree", Importance.OPTIONAL, CandidateAtsProfile::getDegree),
    FIELD_OF_STUDY("fieldOfStudy", Importance.OPTIONAL, CandidateAtsProfile::getFieldOfStudy),
    UNIVERSITY("university", Importance.RECOMMENDED, CandidateAtsProfile::getUniversity),
    GRADUATION_YEAR("graduationYear", Importance.OPTIONAL, p -> integer(p.getGraduationYear())),

    // ── Work authorisation ──
    WORK_AUTHORIZATION("workAuthorization", Importance.REQUIRED, CandidateAtsProfile::getWorkAuthorization),
    VISA_STATUS("visaStatus", Importance.RECOMMENDED, CandidateAtsProfile::getVisaStatus),
    CITIZENSHIP("citizenship", Importance.OPTIONAL, CandidateAtsProfile::getCitizenship),
    SECURITY_CLEARANCE("securityClearance", Importance.OPTIONAL, CandidateAtsProfile::getSecurityClearance),

    // ── Lists ──
    LANGUAGES("languages", Importance.OPTIONAL, p -> joined(p.getLanguagesJson())),
    CERTIFICATIONS("certifications", Importance.OPTIONAL, p -> joined(p.getCertificationsJson()));

    /** How much a missing value actually costs an application. */
    public enum Importance {
        /** Most ATS forms will not submit without it. */
        REQUIRED,
        /** Commonly asked and frequently required on senior or international roles. */
        RECOMMENDED,
        /** Asked by some platforms; absence rarely blocks a submission. */
        OPTIONAL
    }

    private final String fieldName;
    private final Importance importance;
    private final Function<CandidateAtsProfile, String> reader;

    AtsProfileField(String fieldName, Importance importance,
                    Function<CandidateAtsProfile, String> reader) {
        this.fieldName = fieldName;
        this.importance = importance;
        this.reader = reader;
    }

    public String fieldName() {
        return fieldName;
    }

    public Importance importance() {
        return importance;
    }

    /** The stored value, or empty when unset. Never returns a blank string as a present value. */
    public Optional<String> read(CandidateAtsProfile profile) {
        if (profile == null) return Optional.empty();
        String value = reader.apply(profile);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    public static Optional<AtsProfileField> byName(String fieldName) {
        if (fieldName == null) return Optional.empty();
        return Arrays.stream(values()).filter(f -> f.fieldName.equals(fieldName)).findFirst();
    }

    public static List<AtsProfileField> withImportance(Importance importance) {
        return Arrays.stream(values()).filter(f -> f.importance == importance).toList();
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String integer(Integer value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String joined(String json) {
        if (json == null || json.isBlank()) return null;
        List<String> values = JsonLists.toList(json);
        return values.isEmpty() ? null : String.join(", ", values);
    }
}
