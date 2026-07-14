package ai.careerpilot.submission.mapping;

/**
 * Phase 7.16 — one canonical application-form field, with its source and whether it is genuinely
 * derivable. Data-source honesty: {@code unmapped=true} fields carry a {@code null} value — never
 * a fabricated placeholder (see {@code FieldMappingService} for the audited source list).
 */
public record MappedField(String fieldName, String value, String source, boolean unmapped) {

    public static MappedField mapped(String fieldName, String value, String source) {
        return new MappedField(fieldName, value, source, false);
    }

    public static MappedField unmapped(String fieldName) {
        return new MappedField(fieldName, null, "none", true);
    }
}
