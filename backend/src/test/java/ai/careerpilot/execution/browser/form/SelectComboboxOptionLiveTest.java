package ai.careerpilot.execution.browser.form;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P7 Action 5C-FIX — proves {@link FormDiscoveryScript#SELECT_COMBOBOX_OPTION} against a real DOM
 * and a real (non-React, but behaviourally equivalent) open/select interaction, the same "test the
 * actual browser-side script, not a hand-built Java map" discipline as
 * {@code FormDiscoveryScriptLabelResolutionTest} for the Action 3 label fix.
 *
 * <p>The fixture reproduces the structural pattern real component-library comboboxes share: the
 * option list does not exist in a meaningfully queryable state until the control is opened, and the
 * control's own displayed text only changes in response to a genuine click on an option — nothing
 * here is asserted from a pre-filled static value.
 *
 * <p>Every test in this file also asserts a submit-button click counter stays at zero — Hard Safety
 * Rule 10 (no real submit) applies to this verification harness exactly as it did to the live/fixture
 * runs in Actions 5-5C, even though this fixture is not a full application form.
 */
class SelectComboboxOptionLiveTest {

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

    private static final String FIXTURE = """
            <html><body>
              <div id="visa" role="combobox" aria-controls="visa-list" aria-expanded="false" tabindex="0">
                <span id="visa-display">Select...</span>
              </div>
              <ul id="visa-list" role="listbox" style="display:none">
                <li role="option">Yes</li>
                <li role="option">No</li>
              </ul>
              <button type="button" id="submit_application_button" disabled>Submit Application</button>
              <script>
                window.__submitClicks = 0;
                document.getElementById('submit_application_button')
                  .addEventListener('click', () => { window.__submitClicks++; });
                document.getElementById('visa').addEventListener('click', () => {
                  const list = document.getElementById('visa-list');
                  list.style.display = (list.style.display === 'none') ? 'block' : 'none';
                });
                document.querySelectorAll('#visa-list [role="option"]').forEach(opt => {
                  opt.addEventListener('click', () => {
                    document.getElementById('visa-display').textContent = opt.textContent;
                    document.getElementById('visa-list').style.display = 'none';
                  });
                });
              </script>
            </body></html>
            """;

    /** Test B/C — a real answer that genuinely exists among the live-rendered options is selected and verified. */
    @Test
    void answerThatExistsAmongLiveOptionsIsSelectedAndVerified() {
        try (Page page = browser.newPage()) {
            page.setContent(FIXTURE);

            Object raw = page.evaluate(FormDiscoveryScript.SELECT_COMBOBOX_OPTION,
                    Map.of("selector", "#visa", "expected", "No"));
            FormDiscoveryScript.ComboboxSelectionResult result = FormDiscoveryScript.parseComboboxSelection(raw);

            assertThat(result.opened()).isTrue();
            assertThat(result.matched()).isTrue();
            assertThat(result.verified())
                    .as("a click alone is not proof of selection — the control's own displayed text "
                            + "must genuinely have changed to the matched option's text")
                    .isTrue();
            assertThat(result.selectedText()).isEqualTo("No");
            assertThat(result.matchedOptionText()).isEqualTo("No");

            // DOM read-back, independent of the script's own report — the control's live text really did change.
            assertThat(page.textContent("#visa-display")).isEqualTo("No");
            assertThat(page.evaluate("window.__submitClicks")).isEqualTo(0);
        }
    }

    /** Test B/C, boolean-equivalence path — the resolver's literal "false"/"true" must still match Yes/No options. */
    @Test
    void booleanLiteralAnswerMatchesYesNoOptionsTheSameWayFormFillPlannerMatchOptionDoes() {
        try (Page page = browser.newPage()) {
            page.setContent(FIXTURE);
            FormDiscoveryScript.ComboboxSelectionResult result = FormDiscoveryScript.parseComboboxSelection(
                    page.evaluate(FormDiscoveryScript.SELECT_COMBOBOX_OPTION,
                            Map.of("selector", "#visa", "expected", "false")));

            assertThat(result.matched()).isTrue();
            assertThat(result.verified()).isTrue();
            assertThat(result.matchedOptionText()).isEqualTo("No");
            assertThat(page.evaluate("window.__submitClicks")).isEqualTo(0);
        }
    }

    /**
     * Test D — the resolved answer does not exist among the live-rendered options (Maybe/Unsure vs.
     * the real Yes/No). The system must refuse rather than pick the closest-looking option, and the
     * control's displayed value must remain untouched.
     */
    @Test
    void answerWithNoMatchingLiveOptionIsRefusedNotGuessed() {
        try (Page page = browser.newPage()) {
            page.setContent(FIXTURE);

            Object raw = page.evaluate(FormDiscoveryScript.SELECT_COMBOBOX_OPTION,
                    Map.of("selector", "#visa", "expected", "Maybe"));
            FormDiscoveryScript.ComboboxSelectionResult result = FormDiscoveryScript.parseComboboxSelection(raw);

            assertThat(result.matched()).isFalse();
            assertThat(result.verified()).isFalse();
            assertThat(result.reason()).contains("no rendered option matches");
            assertThat(result.availableOptions()).containsExactlyInAnyOrder("Yes", "No");

            // Nothing was clicked: the control's displayed value must be exactly what it was before.
            assertThat(page.textContent("#visa-display")).isEqualTo("Select...");
            assertThat(page.evaluate("window.__submitClicks")).isEqualTo(0);
        }
    }

    /** A control that does not exist on the page is an honest failure, never a fabricated match. */
    @Test
    void missingControlIsAnHonestFailure() {
        try (Page page = browser.newPage()) {
            page.setContent(FIXTURE);
            FormDiscoveryScript.ComboboxSelectionResult result = FormDiscoveryScript.parseComboboxSelection(
                    page.evaluate(FormDiscoveryScript.SELECT_COMBOBOX_OPTION,
                            Map.of("selector", "#does-not-exist", "expected", "No")));

            assertThat(result.matched()).isFalse();
            assertThat(result.reason()).contains("control not found");
        }
    }
}
