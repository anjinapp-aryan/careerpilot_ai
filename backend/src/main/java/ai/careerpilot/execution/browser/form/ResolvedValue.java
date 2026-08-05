package ai.careerpilot.execution.browser.form;

/**
 * Phase 12C — the outcome of asking "do we have a verified value for this field?".
 *
 * <p>Modelled on {@code MappedField} (Phase 7.16), which established the convention this record
 * follows: an unresolved value carries {@code null}, never a placeholder. The addition here is
 * {@code reason} — an unresolved field must be able to say <em>why</em>, because "this platform has
 * no phone number anywhere in its schema" and "this user has not set a salary target" are different
 * problems with different fixes, and collapsing them into a blank hides both.
 *
 * @param value  the verified value, or {@code null} when unresolved
 * @param source provenance, e.g. {@code "User.email"} or {@code "ApplicationSubmissionAnswer[SALARY]"}
 * @param reason why it is unresolved; {@code null} when resolved
 */
public record ResolvedValue(String value, String source, String reason) {

    public static ResolvedValue of(String value, String source) {
        return new ResolvedValue(value, source, null);
    }

    public static ResolvedValue unresolved(String reason) {
        return new ResolvedValue(null, "none", reason);
    }

    /** No data source exists in this platform at all — a product gap, not a missing user value. */
    public static ResolvedValue noDataSource(CanonicalField field) {
        return unresolved("no verified source exists in this platform for " + field);
    }

    public boolean isResolved() {
        return value != null && !value.isBlank();
    }
}
