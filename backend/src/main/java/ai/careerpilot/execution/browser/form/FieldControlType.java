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
    /**
     * P7 Action 5C-FIX — a non-native dropdown built from {@code role="combobox"}/{@code
     * role="listbox"} (React Select, MUI, Ant Design, Radix). Deliberately excluded from
     * {@link #isChoice()}: a closed custom widget's real options are usually not in the DOM yet
     * (rendered lazily on open), so this type is filled by opening the control live and reading
     * its rendered options — see {@code BrowserFormAutomationEngine}'s {@code COMBOBOX} case —
     * rather than by static option-list matching at plan time.
     */
    COMBOBOX,
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
     * Pre-Action-5C-FIX compatibility overload: no {@code role} signal, so a {@code role="combobox"}
     * div (or any other role) can never be recognised — every existing caller that never had a role
     * to pass keeps its exact prior behaviour.
     */
    public static FieldControlType from(String tagName, String inputType, boolean contentEditable) {
        return from(tagName, inputType, contentEditable, "");
    }

    /**
     * Maps a DOM tag + input type to a control type. Unknown input types resolve to {@link #TEXT}
     * rather than {@link #UNSUPPORTED} because the HTML spec requires unknown {@code type}
     * attributes to be treated as {@code text} — that is the browser's own behaviour, not a guess.
     *
     * <p>P7 Action 5C-FIX — {@code role} is checked first, ahead of tag/contenteditable, because a
     * component library's combobox/listbox is a purpose-built widget: the ARIA role is a stronger,
     * more specific signal of intent than the fact that it happens to be rendered as a {@code div}.
     * Only the two roles this engine has a real fill strategy for are recognised; every other role
     * (checkbox/radio/switch/textbox rendered on a non-native element) is unchanged by this method
     * and continues to resolve however the tag/type/contenteditable rules below already resolved it
     * — a real gap for a future action, not one this fix silently papers over.
     */
    public static FieldControlType from(String tagName, String inputType, boolean contentEditable, String role) {
        String r = role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
        if (r.equals("combobox") || r.equals("listbox")) return COMBOBOX;
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
