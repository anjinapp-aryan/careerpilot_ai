package ai.careerpilot.execution.browser.form;

import java.util.Locale;

/**
 * Phase 12C — the HTML control kind of a discovered form field. This is <em>how</em> to interact
 * with a field; {@link CanonicalField} is <em>what the field means</em>. The two are deliberately
 * separate: a salary question can be a text input on Greenhouse and a select on Ashby, and the
 * value resolution must not care which.
 */
public enum FieldControlType {

    TEXT,
    TEXTAREA,
    EMAIL,
    TEL,
    NUMBER,
    DATE,
    SELECT,
    CHECKBOX,
    RADIO,
    FILE,
    /** A contenteditable rich-text editor (Lever/Ashby cover-letter boxes). */
    RICH_TEXT,
    /** Discovered but not something this engine knows how to drive. Never guessed at. */
    UNSUPPORTED;

    /** True when the control accepts free text via a fill/type interaction. */
    public boolean isTextual() {
        return this == TEXT || this == TEXTAREA || this == EMAIL || this == TEL
                || this == NUMBER || this == DATE || this == RICH_TEXT;
    }

    public boolean isChoice() {
        return this == SELECT || this == CHECKBOX || this == RADIO;
    }

    /**
     * Maps a DOM tag + input type to a control type. Unknown input types resolve to {@link #TEXT}
     * rather than {@link #UNSUPPORTED} because the HTML spec requires unknown {@code type}
     * attributes to be treated as {@code text} — that is the browser's own behaviour, not a guess.
     */
    public static FieldControlType from(String tagName, String inputType, boolean contentEditable) {
        if (contentEditable) return RICH_TEXT;
        String tag = tagName == null ? "" : tagName.toLowerCase(Locale.ROOT);
        switch (tag) {
            case "textarea":
                return TEXTAREA;
            case "select":
                return SELECT;
            case "input":
                break;
            default:
                return UNSUPPORTED;
        }
        String type = inputType == null || inputType.isBlank() ? "text" : inputType.toLowerCase(Locale.ROOT);
        return switch (type) {
            case "email" -> EMAIL;
            case "tel" -> TEL;
            case "number" -> NUMBER;
            case "date", "month", "week", "datetime-local" -> DATE;
            case "checkbox" -> CHECKBOX;
            case "radio" -> RADIO;
            case "file" -> FILE;
            // Non-input-bearing controls. Listing them explicitly keeps them out of the fill plan
            // instead of being silently treated as text and typed into.
            case "submit", "button", "reset", "image" -> UNSUPPORTED;
            default -> TEXT;
        };
    }
}
