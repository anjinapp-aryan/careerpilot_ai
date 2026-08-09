package ai.careerpilot.execution.browser.form;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P7 Action 3 — proves the actual browser-side {@link FormDiscoveryScript#DISCOVER_FIELDS} JS
 * against a real DOM, not just Java-side parsing of a hand-built map. The defect this closes
 * lives entirely inside the JS template string, so a test that never executes it (every other
 * {@code DiscoveredField}-based test in this codebase constructs records directly) cannot exercise
 * it at all.
 *
 * <p>The fixture below is modeled on the well-documented, common React-Select DOM pattern — a
 * generic Select component whose {@code aria-label} defaults to its own placeholder ("Select...")
 * when the integration never wires a real accessible name, with the actual question text sitting
 * in a nearby {@code <label>} that is not {@code for}/{@code aria-labelledby} associated to the
 * control. This is the most common, well-established cause of exactly the symptom CLAUDE.md
 * records from a real live run against a Greenhouse posting ("Greenhouse's custom React dropdowns
 * resolve their label as 'Select...' instead of the actual question ... 4 controls lost to
 * UNKNOWN") — it is a representative reproduction of that documented defect class, not a captured
 * copy of GitLab's or any other specific employer's live markup, which was not fetched for this
 * fix (no network access from this environment, and doing so was out of scope regardless).
 *
 * <p>Runs against the local Chromium Playwright already has cached
 * ({@code %LOCALAPPDATA%\ms-playwright}) — this codebase's documented "no browser in CI" caveat
 * (see {@code CaptchaLoginDetector}) is about the deployment target, not this dev environment.
 */
class FormDiscoveryScriptLabelResolutionTest {

    private static Playwright playwright;
    private static Browser browser;

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    static void closeBrowser() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    /**
     * Reproduces the defect: a react-select-shaped combobox whose {@code aria-label} mirrors its
     * own placeholder, with the real question in a nearby but not programmatically associated
     * {@code <label>}. Before the fix, {@code aria-label} won immediately (checked before any
     * placeholder filter applied) and the discovered label was literally {@code "Select..."}.
     */
    @Test
    void placeholderShapedAriaLabelDoesNotOutrankTheNearbyRealQuestion() {
        String html = """
                <html><body>
                  <div class="select-shell">
                    <label>Are you legally authorized to work in the United States?</label>
                    <div class="select__control" role="combobox" aria-label="Select..." tabindex="0">
                      <div class="select__placeholder">Select...</div>
                    </div>
                  </div>
                </body></html>
                """;

        DiscoveredField combobox = discoverSingleComboboxField(html);

        assertThat(combobox.label())
                .as("the real question, not the widget's own placeholder text, must win")
                .isEqualTo("Are you legally authorized to work in the United States?");
        assertThat(combobox.identityText())
                .as("classification input must carry the real question, not 'select...'")
                .contains("are you legally authorized to work in the united states");
    }

    /**
     * Control case: when a combobox's {@code aria-label} genuinely IS the accessible name (not
     * placeholder-shaped), it must still win immediately — the fix must not make every combobox
     * fall through to proximity guessing regardless of whether its authoritative label was real.
     */
    @Test
    void genuineAriaLabelStillWinsImmediately() {
        String html = """
                <html><body>
                  <div class="select-shell">
                    <label>Unrelated nearby text that must NOT be picked up</label>
                    <div class="select__control" role="combobox"
                         aria-label="Do you require visa sponsorship?" tabindex="0">
                      <div class="select__placeholder">Select...</div>
                    </div>
                  </div>
                </body></html>
                """;

        DiscoveredField combobox = discoverSingleComboboxField(html);

        assertThat(combobox.label()).isEqualTo("Do you require visa sponsorship?");
    }

    /**
     * A required field whose only nearby text is itself placeholder-shaped (no real question
     * anywhere discoverable) must resolve to an honest empty/unclassifiable label — never a guess,
     * and never "Select..." masquerading as a real answer. FieldClassifier's own UNKNOWN handling
     * (untouched by this fix) is what then blocks submission on it — verified separately in
     * FieldClassifierTest / BrowserFormAutomationEngine's blocking-gap tests, not re-proven here.
     */
    @Test
    void noRealLabelAnywhereResolvesEmptyRatherThanThePlaceholder() {
        String html = """
                <html><body>
                  <div class="select__control" role="combobox" aria-label="Select..." tabindex="0">
                    <div class="select__placeholder">Select...</div>
                  </div>
                </body></html>
                """;

        DiscoveredField combobox = discoverSingleComboboxField(html);

        assertThat(combobox.label()).isEmpty();
    }

    /**
     * P7 Action 5C-FIX, Step 3 requiredness safety — {@code data-required="true"} is a real
     * requiredness signal for a custom widget that never sets native {@code required}/
     * {@code aria-required} (the exact pattern the HIGH finding's own fixture used). Only the
     * literal string {@code "true"} counts: presence alone does not, since a data-* attribute's
     * meaning is not standardised across ATS platforms.
     */
    @Test
    void dataRequiredTrueIsARequirednessSignalWhenNativeSignalsAreAbsent() {
        String html = """
                <html><body>
                  <div class="select-shell">
                    <label>Will you now or in the future require sponsorship?</label>
                    <div class="select__control" role="combobox" aria-label="Select..."
                         tabindex="0" data-required="true">
                      <div class="select__placeholder">Select...</div>
                    </div>
                  </div>
                </body></html>
                """;
        assertThat(discoverSingleComboboxField(html).required()).isTrue();
    }

    /** A data-required value other than the literal "true" must NOT be trusted as requiredness. */
    @Test
    void dataRequiredWithAnyOtherValueIsNotTrusted() {
        String html = """
                <html><body>
                  <div class="select-shell">
                    <label>Optional widget preference</label>
                    <div class="select__control" role="combobox" aria-label="Select..."
                         tabindex="0" data-required="conditional">
                      <div class="select__placeholder">Select...</div>
                    </div>
                  </div>
                </body></html>
                """;
        assertThat(discoverSingleComboboxField(html).required()).isFalse();
    }

    private DiscoveredField discoverSingleComboboxField(String html) {
        try (Page page = browser.newPage()) {
            page.setContent(html);
            Object result = page.evaluate(FormDiscoveryScript.DISCOVER_FIELDS);
            List<DiscoveredField> fields = FormDiscoveryScript.parse(result);
            List<DiscoveredField> comboboxes = fields.stream()
                    .filter(f -> "combobox".equals(f.role()))
                    .toList();
            assertThat(comboboxes)
                    .as("fixture must discover exactly one combobox control")
                    .hasSize(1);
            return comboboxes.get(0);
        }
    }
}
