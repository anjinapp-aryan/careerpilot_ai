package ai.careerpilot.service.profile.ats;

import ai.careerpilot.domain.CandidateAtsProfile;
import ai.careerpilot.domain.FieldVerificationSource;
import ai.careerpilot.repo.CandidateAtsProfileRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Phase C — the only writer of {@link CandidateAtsProfile}, and the only place provenance is
 * recorded.
 *
 * <p><b>Provenance is recorded on write, never inferred on read.</b> A value whose origin was not
 * captured at the moment it was stored cannot be reconstructed afterwards, and guessing it later is
 * how an AI suggestion would eventually be treated as something the candidate said. A field with no
 * recorded source therefore reads back as {@link FieldVerificationSource#AI_SUGGESTED} — the
 * untrusted band — so the failure mode of forgetting to record provenance is a field automation
 * declines to use, not a field it wrongly trusts.
 *
 * <p>Gated by {@code candidate.ats-profile.enabled} (default {@code false}), matching this
 * codebase's dark-by-default convention. With the flag off, reads return empty and writes are
 * refused, so {@code FieldMappingService} behaves exactly as it did before this phase.
 */
@Service
public class CandidateAtsProfileService {

    private static final Logger log = LoggerFactory.getLogger(CandidateAtsProfileService.class);

    /**
     * Owned rather than injected. This application has no {@code ObjectMapper} bean — injecting one
     * fails context startup, which is exactly what happened the first time this service was
     * deployed. All 36 other Jackson users in this codebase construct their own; {@code ObjectMapper}
     * is thread-safe once configured, so a private instance is also the safer choice for a
     * deterministic service.
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final CandidateAtsProfileRepository repository;
    private final boolean enabled;

    public CandidateAtsProfileService(CandidateAtsProfileRepository repository,
                                      // Dotted, not hyphenated: Spring's relaxed binding maps
                                      // CANDIDATE_ATS_PROFILE_ENABLED onto this exact key, whereas
                                      // a hyphenated `ats-profile` would silently never bind from
                                      // the environment.
                                      @Value("${candidate.ats.profile.enabled:false}") boolean enabled) {
        this.repository = repository;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** The user's ATS profile, or empty when absent or the feature is off. Never throws. */
    public Optional<CandidateAtsProfile> get(UUID userId) {
        if (!enabled || userId == null) return Optional.empty();
        try {
            return repository.findByUserId(userId);
        } catch (Exception e) {
            log.warn("ATS_PROFILE read failed user={}: {}", userId, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Apply a patch. Only fields present in {@code values} are written — a null or absent entry
     * leaves the stored value alone rather than clearing it, so a partial form submission from a
     * guided-completion UI can never wipe data the user did not have on screen.
     *
     * @param values fieldName → new value, using {@link AtsProfileField#fieldName()} names
     * @param source how these particular values were obtained
     */
    @Transactional
    public Optional<CandidateAtsProfile> update(UUID userId, Map<String, Object> values,
                                                FieldVerificationSource source) {
        if (!enabled || userId == null) return Optional.empty();
        FieldVerificationSource provenance = source == null ? FieldVerificationSource.USER_ENTERED : source;

        CandidateAtsProfile profile = repository.findByUserId(userId)
                .orElseGet(() -> CandidateAtsProfile.builder().userId(userId).build());
        Map<String, String> sources = new TreeMap<>(readSources(profile));

        int applied = 0;
        if (values != null) {
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                Object raw = entry.getValue();
                if (raw == null) continue;
                String value = String.valueOf(raw).trim();
                if (value.isEmpty()) continue;
                Optional<AtsProfileField> field = AtsProfileField.byName(entry.getKey());
                if (field.isEmpty()) continue;               // unknown key: ignored, never persisted
                if (!apply(profile, field.get(), value)) continue;
                sources.put(field.get().fieldName(), provenance.name());
                applied++;
            }
        }

        profile.setFieldSourcesJson(writeSources(sources));
        CandidateAtsProfile saved = repository.save(profile);
        log.info("ATS_PROFILE updated user={} fields={} source={}", userId, applied, provenance);
        return Optional.of(saved);
    }

    /** Recorded provenance for one field. Absent provenance is untrusted, never assumed verified. */
    public FieldVerificationSource sourceOf(CandidateAtsProfile profile, String fieldName) {
        if (profile == null || fieldName == null) return FieldVerificationSource.AI_SUGGESTED;
        return FieldVerificationSource.parseOrUntrusted(readSources(profile).get(fieldName));
    }

    /**
     * The value of a field <b>only if</b> it is both present and came from a source automation may
     * use. This is the accessor every automation-facing caller should reach for: an
     * {@link FieldVerificationSource#AI_SUGGESTED} value is deliberately invisible through it.
     */
    public Optional<String> trustedValue(CandidateAtsProfile profile, AtsProfileField field) {
        if (profile == null || field == null) return Optional.empty();
        Optional<String> value = field.read(profile);
        if (value.isEmpty()) return Optional.empty();
        return sourceOf(profile, field.fieldName()).isTrustedForAutomation() ? value : Optional.empty();
    }

    // ── internals ──

    /** @return true when the field was recognised and written */
    private boolean apply(CandidateAtsProfile p, AtsProfileField field, String value) {
        switch (field) {
            case PHONE -> p.setPhone(value);
            case COUNTRY -> p.setCountry(value);
            case CITY -> p.setCity(value);
            case STATE_PROVINCE -> p.setStateProvince(value);
            case ADDRESS_LINE1 -> p.setAddressLine1(value);
            case ADDRESS_LINE2 -> p.setAddressLine2(value);
            case POSTAL_CODE -> p.setPostalCode(value);
            case LINKEDIN_URL -> p.setLinkedinUrl(value);
            case GITHUB_URL -> p.setGithubUrl(value);
            case PORTFOLIO_URL -> p.setPortfolioUrl(value);
            case PERSONAL_WEBSITE_URL -> p.setPersonalWebsiteUrl(value);
            case CURRENT_COMPANY -> p.setCurrentCompany(value);
            case CURRENT_TITLE -> p.setCurrentTitle(value);
            case NOTICE_PERIOD -> p.setNoticePeriod(value);
            case CURRENT_SALARY -> {
                BigDecimal parsed = parseDecimal(value);
                if (parsed == null) return false;
                p.setCurrentSalary(parsed);
            }
            case CURRENT_SALARY_CURRENCY -> p.setCurrentSalaryCurrency(value);
            case HIGHEST_EDUCATION -> p.setHighestEducation(value);
            case DEGREE -> p.setDegree(value);
            case FIELD_OF_STUDY -> p.setFieldOfStudy(value);
            case UNIVERSITY -> p.setUniversity(value);
            case GRADUATION_YEAR -> {
                Integer parsed = parseYear(value);
                if (parsed == null) return false;
                p.setGraduationYear(parsed);
            }
            case WORK_AUTHORIZATION -> p.setWorkAuthorization(value);
            case VISA_STATUS -> p.setVisaStatus(value);
            case CITIZENSHIP -> p.setCitizenship(value);
            case SECURITY_CLEARANCE -> p.setSecurityClearance(value);
            case LANGUAGES -> p.setLanguagesJson(ai.careerpilot.service.profile.JsonLists
                    .toJson(splitList(value)));
            case CERTIFICATIONS -> p.setCertificationsJson(ai.careerpilot.service.profile.JsonLists
                    .toJson(splitList(value)));
        }
        return true;
    }

    private static java.util.List<String> splitList(String value) {
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** A non-numeric salary is rejected rather than coerced — a wrong number is worse than none. */
    private static BigDecimal parseDecimal(String value) {
        try {
            return new BigDecimal(value.replaceAll("[,\\s]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseYear(String value) {
        try {
            int year = Integer.parseInt(value.trim());
            // A graduation year outside this range is a typo, not a fact worth storing.
            return (year >= 1900 && year <= 2100) ? year : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, String> readSources(CandidateAtsProfile profile) {
        String json = profile.getFieldSourcesJson();
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, String> parsed = objectMapper.readValue(json, new TypeReference<>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            // Unreadable provenance means every field reads back untrusted, which is the safe
            // direction: automation declines rather than trusting an unverifiable value.
            log.warn("ATS_PROFILE unreadable field_sources for user={}: {}",
                    profile.getUserId(), e.toString());
            return Map.of();
        }
    }

    private String writeSources(Map<String, String> sources) {
        try {
            return objectMapper.writeValueAsString(new LinkedHashMap<>(sources));
        } catch (Exception e) {
            log.warn("ATS_PROFILE could not serialise field_sources: {}", e.toString());
            return null;
        }
    }
}
