package ai.careerpilot.execution.browser.form;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase B — regression coverage for the phantom-suppression rule.
 *
 * <p>Every component-library case is expressed as plain records rather than a live page. That is
 * the point of doing reduction in Java: the rule that decides a control is disposable is the one
 * rule in discovery whose failure mode is a silently incomplete application, so it is tested
 * exhaustively and without a browser.
 *
 * <p>The fixtures mirror what each library genuinely emits, taken from the Phase A field dump for
 * React Select and from each library's documented DOM shape for the others.
 */
class FormControlReducerTest {

    private static final String WIDGET = "/html[1]/body[1]/div[3]";

    /** A real, visible, addressable, labelled control. */
    private static DiscoveredField real(String id, String label, String widgetKey) {
        return field("#" + id, id, "", label, false, widgetKey, "", 0, "combobox");
    }

    /** A framework helper: invisible, no id, no name, placeholder label. */
    private static DiscoveredField helper(String selector, String label, String widgetKey) {
        return field(selector, "", "", label, true, widgetKey, "", 0, "");
    }

    private static DiscoveredField field(String selector, String id, String name, String label,
                                         boolean hidden, String widgetKey, String framePath,
                                         int shadowDepth, String role) {
        return new DiscoveredField(selector, FieldControlType.TEXT, name, id, label, "", "", "",
                "", true, hidden, false, false, -1, role, "/x", List.of(),
                widgetKey, framePath, shadowDepth);
    }

    @Nested
    @DisplayName("component libraries emit one logical field, not several")
    class ComponentLibraries {

        @Test
        @DisplayName("React Select: the visible combobox survives, the hidden Select... helper does not")
        void reactSelect() {
            // Exactly the pair Phase A found on a live Greenhouse posting.
            List<DiscoveredField> raw = List.of(
                    real("question_36101207002", "Are you subject to any employment agreements?", WIDGET),
                    helper("input:nth-of-type(7)", "Select...", WIDGET));

            FormControlReducer.Reduction r = FormControlReducer.reduce(raw, null);

            assertThat(r.fields()).singleElement()
                    .extracting(DiscoveredField::id).isEqualTo("question_36101207002");
            assertThat(r.diagnostics().phantomsRemoved()).isEqualTo(1);
        }

        @Test
        @DisplayName("Material UI: the hidden native input backing an Autocomplete is collapsed")
        void materialUi() {
            List<DiscoveredField> raw = List.of(
                    real("mui-autocomplete-1", "Country of residence", WIDGET),
                    helper("input:nth-of-type(2)", "", WIDGET));

            FormControlReducer.Reduction r = FormControlReducer.reduce(raw, null);

            assertThat(r.fields()).singleElement()
                    .extracting(DiscoveredField::label).isEqualTo("Country of residence");
            assertThat(r.diagnostics().phantomsRemoved()).isEqualTo(1);
        }

        @Test
        @DisplayName("Ant Design: the mirror input carrying only placeholder text is collapsed")
        void antDesign() {
            List<DiscoveredField> raw = List.of(
                    real("notice_period", "Notice period", WIDGET),
                    helper("input:nth-of-type(4)", "Please select", WIDGET));

            FormControlReducer.Reduction r = FormControlReducer.reduce(raw, null);

            assertThat(r.fields()).singleElement()
                    .extracting(DiscoveredField::id).isEqualTo("notice_period");
        }

        @Test
        @DisplayName("HeadlessUI and Radix: hidden state-holder inputs are collapsed")
        void headlessUiAndRadix() {
            List<DiscoveredField> raw = List.of(
                    real("headless-combobox-3", "Preferred location", "/w1"),
                    helper("input:nth-of-type(5)", "Choose...", "/w1"),
                    real("radix-select-9", "Work authorisation", "/w2"),
                    helper("input:nth-of-type(6)", "Select…", "/w2"));

            FormControlReducer.Reduction r = FormControlReducer.reduce(raw, null);

            assertThat(r.fields()).extracting(DiscoveredField::id)
                    .containsExactly("headless-combobox-3", "radix-select-9");
            assertThat(r.diagnostics().phantomsRemoved()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("suppression refuses to remove anything that might be real")
    class Safety {

        @Test
        @DisplayName("a visible control is never removed, even with no id, name or label")
        void visibleControlSurvives() {
            List<DiscoveredField> raw = List.of(
                    real("first_name", "First name", WIDGET),
                    field("input:nth-of-type(2)", "", "", "", false, WIDGET, "", 0, ""));

            FormControlReducer.Reduction r = FormControlReducer.reduce(raw, null);

            assertThat(r.fields()).hasSize(2);
            assertThat(r.diagnostics().phantomsRemoved()).isZero();
        }

        @Test
        @DisplayName("a hidden helper alone in its group survives — there is nothing to collapse onto")
        void loneHiddenControlSurvives() {
            List<DiscoveredField> raw = List.of(helper("input:nth-of-type(1)", "Select...", WIDGET));

            FormControlReducer.Reduction r = FormControlReducer.reduce(raw, null);

            assertThat(r.fields()).hasSize(1);
            assertThat(r.diagnostics().phantomsRemoved()).isZero();
        }

        @Test
        @DisplayName("a group with no substantive control keeps every member")
        void groupWithoutSurvivorKeepsEverything() {
            List<DiscoveredField> raw = List.of(
                    helper("input:nth-of-type(1)", "Select...", WIDGET),
                    helper("input:nth-of-type(2)", "Select...", WIDGET));

            FormControlReducer.Reduction r = FormControlReducer.reduce(raw, null);

            assertThat(r.fields()).hasSize(2);
        }

        @Test
        @DisplayName("a hidden control with an id is kept — it is addressable, so it is real")
        void hiddenButAddressableSurvives() {
            List<DiscoveredField> raw = List.of(
                    real("visible_one", "Visible question", WIDGET),
                    field("#state_holder", "state_holder", "", "", true, WIDGET, "", 0, ""));

            FormControlReducer.Reduction r = FormControlReducer.reduce(raw, null);

            assertThat(r.fields()).hasSize(2);
        }

        @Test
        @DisplayName("controls in different widgets are never grouped together")
        void separateWidgetsAreIndependent() {
            List<DiscoveredField> raw = List.of(
                    real("q1", "Question one", "/w1"),
                    helper("input:nth-of-type(2)", "Select...", "/w2"));

            FormControlReducer.Reduction r = FormControlReducer.reduce(raw, null);

            // The helper's own group has no substantive member, so it is kept.
            assertThat(r.fields()).hasSize(2);
        }

        @Test
        @DisplayName("a hidden helper wearing the real control's own label is still collapsed")
        void helperDuplicatingTheRealLabelIsCollapsed() {
            // The regression this rule exists for: improving label extraction once gave these
            // helpers a real-looking label, which silently disabled phantom suppression entirely.
            List<DiscoveredField> raw = List.of(
                    real("q1", "Do you require visa sponsorship?", WIDGET),
                    field("input:nth-of-type(3)", "", "", "do you require visa sponsorship?",
                            true, WIDGET, "", 0, ""));

            FormControlReducer.Reduction r = FormControlReducer.reduce(raw, null);

            assertThat(r.fields()).singleElement()
                    .extracting(DiscoveredField::id).isEqualTo("q1");
            assertThat(r.diagnostics().phantomsRemoved()).isEqualTo(1);
        }

        @Test
        @DisplayName("a hidden control asking a genuinely different question is kept")
        void hiddenControlWithItsOwnQuestionSurvives() {
            List<DiscoveredField> raw = List.of(
                    real("q1", "Do you require visa sponsorship?", WIDGET),
                    field("input:nth-of-type(3)", "", "", "What is your notice period?",
                            true, WIDGET, "", 0, ""));

            FormControlReducer.Reduction r = FormControlReducer.reduce(raw, null);

            assertThat(r.fields()).hasSize(2);
            assertThat(r.diagnostics().phantomsRemoved()).isZero();
        }

        @Test
        @DisplayName("a question that merely contains the word 'select' is a real label")
        void realQuestionContainingSelectIsNotAPlaceholder() {
            assertThat(FormControlReducer.isPlaceholderLabel("Select the office closest to you")).isFalse();
            assertThat(FormControlReducer.isPlaceholderLabel("Select...")).isTrue();
            assertThat(FormControlReducer.isPlaceholderLabel("Select…")).isTrue();
            assertThat(FormControlReducer.isPlaceholderLabel("Please select")).isTrue();
            assertThat(FormControlReducer.isPlaceholderLabel("Search...")).isTrue();
            assertThat(FormControlReducer.isPlaceholderLabel("  --  ")).isTrue();
            assertThat(FormControlReducer.isPlaceholderLabel("")).isTrue();
        }
    }

    @Nested
    @DisplayName("traversal")
    class Traversal {

        @Test
        @DisplayName("the same selector in two frames is two controls, not a duplicate")
        void frameParticipatesInIdentity() {
            List<DiscoveredField> raw = List.of(
                    field("#email", "email", "", "Email", false, "", "", 0, ""),
                    field("#email", "email", "", "Email", false, "", "iframe:nth-of-type(1)", 0, ""));

            FormControlReducer.Reduction r = FormControlReducer.reduce(raw, null);

            assertThat(r.fields()).hasSize(2);
            assertThat(r.diagnostics().duplicatesRemoved()).isZero();
            assertThat(r.diagnostics().frameControls()).isEqualTo(1);
        }

        @Test
        @DisplayName("an element re-emitted by overlapping roots is counted once")
        void exactDuplicatesAreRemoved() {
            DiscoveredField f = field("#email", "email", "", "Email", false, "", "", 0, "");

            FormControlReducer.Reduction r = FormControlReducer.reduce(List.of(f, f), null);

            assertThat(r.fields()).hasSize(1);
            assertThat(r.diagnostics().duplicatesRemoved()).isEqualTo(1);
        }

        @Test
        @DisplayName("a shadow-DOM control stays fillable; an iframe control does not")
        void fillabilityReflectsWhatPlaywrightCanActuallyReach() {
            DiscoveredField inShadow = field("#s", "s", "", "Shadow field", false, "", "", 2, "");
            DiscoveredField inFrame = field("#f", "f", "", "Frame field", false, "", "iframe:nth-of-type(1)", 0, "");

            // Playwright's CSS engine pierces open shadow roots but does not cross frames.
            assertThat(inShadow.isFillable()).isTrue();
            assertThat(inFrame.isFillable()).isFalse();
            assertThat(inFrame.inFrame()).isTrue();

            FormControlReducer.Reduction r = FormControlReducer.reduce(List.of(inShadow, inFrame), null);
            assertThat(r.diagnostics().shadowControls()).isEqualTo(1);
            assertThat(r.diagnostics().frameControls()).isEqualTo(1);
        }

        @Test
        @DisplayName("a cross-origin frame is reported honestly, never worked around")
        void crossOriginFrameIsReported() {
            var counters = new DiscoveryDiagnostics.RawCounters(0, 1, 0, 0, 2);

            FormControlReducer.Reduction r = FormControlReducer.reduce(List.of(), counters);

            assertThat(r.diagnostics().crossOriginFrames()).isEqualTo(2);
            assertThat(r.diagnostics().notes())
                    .anyMatch(n -> n.contains("cross-origin") && n.contains("security boundary"));
        }
    }

    @Nested
    @DisplayName("diagnostics")
    class Diagnostics {

        @Test
        @DisplayName("label quality counts placeholder-only labels as unlabelled")
        void labelQualityRejectsPlaceholders() {
            List<DiscoveredField> raw = List.of(
                    field("#a", "a", "", "Real question", false, "", "", 0, ""),
                    field("#b", "b", "", "Select...", false, "", "", 0, ""));

            FormControlReducer.Reduction r = FormControlReducer.reduce(raw, null);

            assertThat(r.fields()).hasSize(2);
            assertThat(r.diagnostics().unlabelledControls()).isEqualTo(1);
            assertThat(r.diagnostics().labelQuality()).isEqualTo(50);
        }

        @Test
        @DisplayName("an empty page reports full label quality rather than zero")
        void emptyPageIsNotADiscoveryFailure() {
            assertThat(FormControlReducer.reduce(List.of(), null).diagnostics().labelQuality())
                    .isEqualTo(100);
        }

        @Test
        @DisplayName("reduction preserves ordering, which downstream alignment depends on")
        void orderingIsPreserved() {
            List<DiscoveredField> raw = List.of(
                    real("one", "One", "/w1"),
                    helper("input:nth-of-type(9)", "Select...", "/w1"),
                    real("two", "Two", "/w2"),
                    real("three", "Three", "/w3"));

            FormControlReducer.Reduction r = FormControlReducer.reduce(raw, null);

            assertThat(r.fields()).extracting(DiscoveredField::id)
                    .containsExactly("one", "two", "three");
        }

        @Test
        @DisplayName("null and empty input never throw")
        void nullSafe() {
            assertThat(FormControlReducer.reduce(null, null).fields()).isEmpty();
            assertThat(FormControlReducer.reduce(List.of(), null).diagnostics())
                    .isEqualTo(DiscoveryDiagnostics.empty().withDuration(0));
        }
    }
}
