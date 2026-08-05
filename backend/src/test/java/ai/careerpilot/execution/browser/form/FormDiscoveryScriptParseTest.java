package ai.careerpilot.execution.browser.form;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase B — the parse boundary between the browser and Java.
 *
 * <p>The script's return shape changed from a bare array to {@code {fields, diagnostics}}. Both
 * shapes must parse, because a stale page (a tab left open across a redeploy) can still return the
 * old form, and returning nothing at all would look identical to a page with no form.
 */
class FormDiscoveryScriptParseTest {

    private static Map<String, Object> row(String selector, String id, String label) {
        Map<String, Object> m = new HashMap<>();
        m.put("selector", selector);
        m.put("tag", "input");
        m.put("type", "text");
        m.put("id", id);
        m.put("label", label);
        m.put("required", true);
        m.put("hidden", false);
        return m;
    }

    @Test
    @DisplayName("the Phase B envelope is parsed into fields plus traversal counters")
    void parsesEnvelope() {
        Map<String, Object> diagnostics = new HashMap<>();
        diagnostics.put("rawCandidates", 7);
        diagnostics.put("rootsTraversed", 3);
        diagnostics.put("shadowRootsFound", 1);
        diagnostics.put("sameOriginFrames", 1);
        diagnostics.put("crossOriginFrames", 2);

        Map<String, Object> envelope = new HashMap<>();
        envelope.put("fields", List.of(row("#a", "a", "First name")));
        envelope.put("diagnostics", diagnostics);

        assertThat(FormDiscoveryScript.parse(envelope)).singleElement()
                .extracting(DiscoveredField::id).isEqualTo("a");

        DiscoveryDiagnostics.RawCounters counters = FormDiscoveryScript.parseRawCounters(envelope);
        assertThat(counters.rawCandidates()).isEqualTo(7);
        assertThat(counters.crossOriginFrames()).isEqualTo(2);
        assertThat(counters.shadowRootsFound()).isEqualTo(1);
    }

    @Test
    @DisplayName("a pre-Phase-B bare array still parses")
    void parsesLegacyArray() {
        assertThat(FormDiscoveryScript.parse(List.of(row("#a", "a", "Email"))))
                .singleElement().extracting(DiscoveredField::label).isEqualTo("Email");
    }

    @Test
    @DisplayName("a result carrying no diagnostics reports unknown counters rather than throwing")
    void missingDiagnosticsAreUnknown() {
        assertThat(FormDiscoveryScript.parseRawCounters(List.of()))
                .isEqualTo(DiscoveryDiagnostics.RawCounters.unknown());
        assertThat(FormDiscoveryScript.parseRawCounters(null))
                .isEqualTo(DiscoveryDiagnostics.RawCounters.unknown());
    }

    @Test
    @DisplayName("widget key, frame path and shadow depth survive the round trip")
    void carriesPhaseBSignals() {
        Map<String, Object> m = row("#q", "q", "Question");
        m.put("widgetKey", "/html[1]/div[2]");
        m.put("framePath", "iframe:nth-of-type(1)");
        m.put("shadowDepth", 2);

        DiscoveredField f = FormDiscoveryScript.parse(Map.of("fields", List.of(m))).get(0);

        assertThat(f.widgetKey()).isEqualTo("/html[1]/div[2]");
        assertThat(f.framePath()).isEqualTo("iframe:nth-of-type(1)");
        assertThat(f.shadowDepth()).isEqualTo(2);
        assertThat(f.inFrame()).isTrue();
    }

    @Test
    @DisplayName("one malformed control costs only itself")
    void malformedRowIsSkippedNotFatal() {
        List<Object> rows = new ArrayList<>();
        rows.add(row("#a", "a", "Good"));
        rows.add("not a control");
        rows.add(row("", "", "no selector"));
        rows.add(row("#b", "b", "Also good"));

        assertThat(FormDiscoveryScript.parse(Map.of("fields", rows)))
                .extracting(DiscoveredField::id).containsExactly("a", "b");
    }

    @Test
    @DisplayName("the script queries the ARIA roles component libraries actually use")
    void scriptCoversAriaRoles() {
        // A guard on the script text itself: dropping one of these silently blinds discovery to an
        // entire component library, and no Java test would otherwise notice.
        assertThat(FormDiscoveryScript.DISCOVER_FIELDS)
                .contains("[role=\"combobox\"]")
                .contains("[role=\"textbox\"]")
                .contains("[role=\"listbox\"]")
                .contains("[role=\"checkbox\"]")
                .contains("[role=\"radio\"]")
                .contains("[role=\"switch\"]")
                .contains("shadowRoot")
                .contains("contentDocument");
    }
}
