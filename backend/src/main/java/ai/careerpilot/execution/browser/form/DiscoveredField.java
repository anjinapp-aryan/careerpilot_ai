package ai.careerpilot.execution.browser.form;

import java.util.List;

/**
 * Phase 12C — one form control as observed in the live DOM, before any interpretation.
 *
 * <p>Deliberately a plain record with no Playwright type in it: discovery happens in the browser
 * (see {@code FormDiscoveryScript}), and everything downstream — classification, answer
 * resolution, planning — is pure Java over these records. That is what makes the entire
 * truthfulness path unit-testable without launching a browser.
 *
 * <p>Carries <b>every</b> identifying signal the DOM offers rather than a selector alone, because
 * real ATS forms identify the same semantic field differently: Greenhouse uses {@code id},
 * Lever uses {@code name}, Ashby leans on {@code aria-label}, and several use only a {@code
 * <label for>} association. Classifying on one signal would work on one vendor and fail on the rest.
 *
 * @param selector    a stable selector Playwright can act on, produced by the discovery script
 * @param controlType how to interact with it
 * @param name        {@code name} attribute
 * @param id          {@code id} attribute
 * @param label       associated label text (via {@code for}/{@code id}, wrapping label, or aria)
 * @param ariaLabel   {@code aria-label}
 * @param placeholder {@code placeholder}
 * @param autocomplete {@code autocomplete} token — the most reliable signal when present
 * @param dataAttributes flattened {@code data-*} values, joined; some ATSes put the semantic here
 * @param required    the control is required (native or aria)
 * @param hidden      not visible to a user
 * @param disabled    disabled
 * @param readOnly    read-only
 * @param maxLength   {@code maxlength} attribute, or {@code -1} when the control imposes no limit
 * @param role        ARIA {@code role}; some ATSes give a {@code div} a role instead of using a
 *                    native control, and the role is then the only thing identifying its kind
 * @param xpath       absolute XPath fallback, for diagnostics and for the (rare) control whose CSS
 *                    selector is ambiguous. <b>Reporting only</b> — the engine acts on
 *                    {@link #selector}, since an absolute XPath breaks on any DOM change
 * @param options     available option labels for SELECT/RADIO groups; empty otherwise
 * @param widgetKey   Phase B — an identifier for the composite widget this control belongs to, or
 *                    empty when it stands alone. Component libraries render one logical field as
 *                    several DOM controls; this is the key {@code FormControlReducer} groups on to
 *                    collapse them back into one. Produced by the discovery script, never guessed
 * @param framePath   Phase B — the same-origin iframe chain this control was found in, empty for
 *                    the top document. <b>Load-bearing, not cosmetic:</b> Playwright's CSS engine
 *                    pierces open shadow roots but does <em>not</em> cross frame boundaries, so a
 *                    control with a non-empty frame path is discovered and reported but is not
 *                    drivable by {@link #selector} alone — see {@link #isFillable()}
 * @param shadowDepth Phase B — how many open shadow roots deep the control sits; {@code 0} for the
 *                    light DOM. Diagnostics only, since Playwright pierces open roots natively
 */
public record DiscoveredField(
        String selector,
        FieldControlType controlType,
        String name,
        String id,
        String label,
        String ariaLabel,
        String placeholder,
        String autocomplete,
        String dataAttributes,
        boolean required,
        boolean hidden,
        boolean disabled,
        boolean readOnly,
        int maxLength,
        String role,
        String xpath,
        List<String> options,
        String widgetKey,
        String framePath,
        int shadowDepth) {

    /**
     * Phase 12C compatibility constructor. Phase 12C.5 added {@code role}/{@code xpath}, which are
     * diagnostics-only and irrelevant to the fill path — this keeps the many call sites that build
     * a field without them (chiefly tests) unchanged rather than churning them for two null values.
     */
    public DiscoveredField(String selector, FieldControlType controlType, String name, String id,
                           String label, String ariaLabel, String placeholder, String autocomplete,
                           String dataAttributes, boolean required, boolean hidden, boolean disabled,
                           boolean readOnly, int maxLength, List<String> options) {
        this(selector, controlType, name, id, label, ariaLabel, placeholder, autocomplete,
                dataAttributes, required, hidden, disabled, readOnly, maxLength, "", "", options);
    }

    /**
     * Phase 12C.5 compatibility constructor. Phase B added {@code widgetKey}/{@code framePath}/
     * {@code shadowDepth}; a control built without them is a top-document, non-composite control,
     * which is exactly what every pre-Phase-B call site meant.
     */
    public DiscoveredField(String selector, FieldControlType controlType, String name, String id,
                           String label, String ariaLabel, String placeholder, String autocomplete,
                           String dataAttributes, boolean required, boolean hidden, boolean disabled,
                           boolean readOnly, int maxLength, String role, String xpath,
                           List<String> options) {
        this(selector, controlType, name, id, label, ariaLabel, placeholder, autocomplete,
                dataAttributes, required, hidden, disabled, readOnly, maxLength, role, xpath,
                options, "", "", 0);
    }

    public DiscoveredField {
        options = options == null ? List.of() : List.copyOf(options);
        role = role == null ? "" : role;
        xpath = xpath == null ? "" : xpath;
        widgetKey = widgetKey == null ? "" : widgetKey;
        framePath = framePath == null ? "" : framePath;
        shadowDepth = Math.max(0, shadowDepth);
        // Normalise "no limit" to a single representation; browsers report 0 and -1 both.
        maxLength = maxLength <= 0 ? -1 : maxLength;
    }

    /** True when this control was found inside a same-origin iframe rather than the top document. */
    public boolean inFrame() {
        return !framePath.isEmpty();
    }

    public boolean hasMaxLength() {
        return maxLength > 0;
    }

    /**
     * Whether this engine may write to the field at all. A hidden/disabled/read-only control is
     * never touched: forcing a value into one is how automation silently corrupts a form's own
     * state machine (an ATS often disables a field until a prior step validates).
     *
     * <p>Phase B adds the frame condition. Playwright's CSS engine pierces open shadow roots, so a
     * shadow-DOM control remains drivable by its selector — but it does not cross an iframe
     * boundary, which needs a frame locator the fill engine does not use. Reporting such a control
     * as fillable would produce a plan whose fills silently match nothing, or worse match a
     * same-named control in the top document.
     */
    public boolean isFillable() {
        return !hidden && !disabled && !readOnly && !inFrame()
                && controlType != FieldControlType.UNSUPPORTED;
    }

    /**
     * All human-readable identifying text, lowercased and joined — the input to classification.
     * Order matters only for readability; matching is substring-based over the whole blob.
     */
    public String identityText() {
        StringBuilder sb = new StringBuilder();
        append(sb, label);
        append(sb, ariaLabel);
        append(sb, placeholder);
        append(sb, name);
        append(sb, id);
        append(sb, autocomplete);
        append(sb, dataAttributes);
        return sb.toString().toLowerCase(java.util.Locale.ROOT);
    }

    private static void append(StringBuilder sb, String value) {
        if (value == null || value.isBlank()) return;
        if (sb.length() > 0) sb.append(' ');
        sb.append(value.trim());
    }

    /** The best single human-readable name for this field, for reporting. Never null. */
    public String displayName() {
        if (label != null && !label.isBlank()) return label.trim();
        if (ariaLabel != null && !ariaLabel.isBlank()) return ariaLabel.trim();
        if (placeholder != null && !placeholder.isBlank()) return placeholder.trim();
        if (name != null && !name.isBlank()) return name.trim();
        if (id != null && !id.isBlank()) return id.trim();
        return selector;
    }
}
