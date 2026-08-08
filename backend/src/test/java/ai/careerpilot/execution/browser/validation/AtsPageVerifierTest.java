package ai.careerpilot.execution.browser.validation;

import ai.careerpilot.execution.browser.validation.AtsPageVerifier.FormSignals;
import ai.careerpilot.execution.browser.validation.AtsPageVerifier.Outcome;
import ai.careerpilot.execution.browser.validation.AtsPageVerifier.PageIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase F4 — the verifier that stops discovery running against a page that is not an application
 * form.
 *
 * <p>Every fixture below is built from <b>measured</b> behaviour, not imagined behaviour. The
 * headline case is the reported bug: {@code https://boards.greenhouse.io/gitlab/jobs/12345} does not
 * 404. Greenhouse answers HTTP 200 after two redirects and serves the company's board index at
 * {@code https://job-boards.greenhouse.io/gitlab?error=true}, titled "Jobs at GitLab" — which is
 * exactly why navigation succeeded and the old harness scored a page that was never a form.
 */
class AtsPageVerifierTest {

    /** A page that genuinely is an application form. */
    private static FormSignals realForm() {
        return new FormSignals(2, 1, 8, 1, true, false);
    }

    /** A board index: links and headings, no form. */
    private static FormSignals noForm() {
        return new FormSignals(0, 0, 0, 0, false, false);
    }

    private static PageIdentity identity(String requested, String finalUrl, String title,
                                         String text, FormSignals signals) {
        return new PageIdentity(requested, finalUrl, title, text, signals);
    }

    // ── The reported bug ──

    @Nested
    @DisplayName("the reported production bug")
    class ReportedBug {

        private Outcome verifyFakeGreenhouse() {
            return AtsPageVerifier.verify(identity(
                    "https://boards.greenhouse.io/gitlab/jobs/12345",
                    "https://job-boards.greenhouse.io/gitlab?error=true",
                    "Jobs at GitLab",
                    "Jobs at GitLab\nAll Departments\nEngineering\nSales\nView all openings",
                    noForm()), AtsPlatform.GREENHOUSE);
        }

        @Test
        @DisplayName("a fake Greenhouse posting is rejected — never a successful validation")
        void fakeGreenhouseUrlIsRejected() {
            Outcome outcome = verifyFakeGreenhouse();

            assertThat(outcome.valid()).isFalse();
            assertThat(outcome.status()).isEqualTo(ValidationReport.Status.INVALID_POSTING);
            assertThat(outcome.status()).isNotEqualTo(ValidationReport.Status.COMPLETED);
        }

        @Test
        @DisplayName("the reason names what actually happened, not a generic failure")
        void reasonExplainsTheRedirect() {
            Outcome outcome = verifyFakeGreenhouse();

            assertThat(outcome.reason()).contains("does not exist");
            assertThat(outcome.reason()).contains("Greenhouse");
            assertThat(outcome.evidence())
                    .anyMatch(e -> e.contains("lost its posting identity"))
                    .anyMatch(e -> e.contains("error=true"));
        }

        @Test
        @DisplayName("rejection is driven by the redirect, not by the word 404 in the markup")
        void doesNotRelyOnKeywordsThatAppearInAssetNames() {
            // Measured: the literal "404" appears in asset filenames on this perfectly ordinary
            // board index. The verifier only ever sees innerText, and the rule that fires here is
            // structural, so a page containing "404" in its markup is not mistaken for a dead job.
            Outcome outcome = verifyFakeGreenhouse();
            assertThat(outcome.status()).isNotEqualTo(ValidationReport.Status.JOB_REMOVED);
        }
    }

    // ── Every rejection category ──

    @Test
    @DisplayName("removed job — page says the posting is gone")
    void removedJobIsJobRemoved() {
        Outcome outcome = AtsPageVerifier.verify(identity(
                "https://boards.greenhouse.io/acme/jobs/999",
                "https://boards.greenhouse.io/acme/jobs/999",
                "Job not found",
                "Job not found. This position is no longer available.",
                noForm()), AtsPlatform.GREENHOUSE);

        assertThat(outcome.valid()).isFalse();
        assertThat(outcome.status()).isEqualTo(ValidationReport.Status.JOB_REMOVED);
    }

    @ParameterizedTest(name = "\"{0}\" reads as a removed posting")
    @ValueSource(strings = {
            "This position is no longer available",
            "We are no longer accepting applications for this role",
            "Position closed",
            "This posting has expired",
            "Error 404 — page not found",
            "This position has been filled"})
    void removalPhrasesAreRecognised(String text) {
        Outcome outcome = AtsPageVerifier.verify(identity(
                "https://jobs.lever.co/acme/abc/apply", "https://jobs.lever.co/acme/abc/apply",
                "Acme", text, noForm()), AtsPlatform.LEVER);

        assertThat(outcome.status()).isEqualTo(ValidationReport.Status.JOB_REMOVED);
    }

    @Test
    @DisplayName("login wall — detected by wording")
    void loginWallIsLoginRequired() {
        Outcome outcome = AtsPageVerifier.verify(identity(
                "https://acme.myworkdayjobs.com/careers/job/123/apply",
                "https://acme.myworkdayjobs.com/login",
                "Sign In",
                "Please sign in to continue to your application.",
                noForm()), AtsPlatform.WORKDAY);

        assertThat(outcome.status()).isEqualTo(ValidationReport.Status.LOGIN_REQUIRED);
    }

    @Test
    @DisplayName("login wall — detected structurally when the wording is unfamiliar")
    void passwordFieldWithoutFormSignalsIsLoginRequired() {
        FormSignals loginPage = new FormSignals(0, 1, 1, 1, false, true);
        Outcome outcome = AtsPageVerifier.verify(identity(
                "https://jobs.lever.co/acme/abc/apply", "https://jobs.lever.co/acme/abc/apply",
                "Acme", "Willkommen. Bitte anmelden.", loginPage), AtsPlatform.LEVER);

        assertThat(outcome.status()).isEqualTo(ValidationReport.Status.LOGIN_REQUIRED);
    }

    @Test
    @DisplayName("a real application form that happens to include a password field is NOT a login wall")
    void applicationFormWithPasswordIsStillValid() {
        // Some ATSes offer "create an account" alongside the form. Form signals win.
        FormSignals formWithAccountCreation = new FormSignals(1, 1, 6, 1, true, true);
        Outcome outcome = AtsPageVerifier.verify(identity(
                "https://jobs.lever.co/acme/abc/apply", "https://jobs.lever.co/acme/abc/apply",
                "Acme - Engineer", "Submit your application", formWithAccountCreation),
                AtsPlatform.LEVER);

        assertThat(outcome.valid()).isTrue();
    }

    @Test
    @DisplayName("redirect to a different site entirely")
    void offSiteRedirectIsRedirectedToNonApplication() {
        Outcome outcome = AtsPageVerifier.verify(identity(
                "https://boards.greenhouse.io/acme/jobs/1",
                "https://www.acme.com/careers",
                "Careers | Acme",
                "Explore opportunities at Acme",
                noForm()), AtsPlatform.GREENHOUSE);

        assertThat(outcome.status()).isEqualTo(ValidationReport.Status.REDIRECTED_TO_NON_APPLICATION);
        assertThat(outcome.reason()).contains("greenhouse.io").contains("acme.com");
    }

    @Test
    @DisplayName("careers homepage pasted directly")
    void careersHomepageIsInvalidPosting() {
        Outcome outcome = AtsPageVerifier.verify(identity(
                "https://boards.greenhouse.io/gitlab",
                "https://job-boards.greenhouse.io/gitlab",
                "Jobs at GitLab",
                "Jobs at GitLab\nAll Departments",
                noForm()), AtsPlatform.GREENHOUSE);

        assertThat(outcome.valid()).isFalse();
        assertThat(outcome.status()).isEqualTo(ValidationReport.Status.INVALID_POSTING);
    }

    @Test
    @DisplayName("a real posting whose title ends with the board name is NOT a board index")
    void companyPortalTitleSuffixIsNotABoardIndex() {
        // Measured false positive from the P1 custom-domain rollout: Airbnb serves the genuine
        // posting /positions/8053756 with the title "… - Careers at Airbnb". The title rule alone
        // called it a board index and reported the wrong reason for a page whose real problem was
        // an iframe-hosted form. The URL still naming a posting is what distinguishes the two.
        Outcome outcome = AtsPageVerifier.verify(identity(
                "https://careers.airbnb.com/positions/8053756",
                "https://careers.airbnb.com/positions/8053756",
                "Senior Principal, Competitive Intelligence - Careers at Airbnb",
                "Senior Principal, Competitive Intelligence",
                noForm()), AtsPlatform.UNKNOWN);

        assertThat(outcome.status()).isNotEqualTo(ValidationReport.Status.INVALID_POSTING);
        assertThat(outcome.reason()).doesNotContain("job board or search page");
    }

    @Test
    @DisplayName("the same title on a board URL IS still a board index")
    void boardUrlWithBoardTitleIsStillRejectedAsAnIndex() {
        // The guard must not disable the rule — only require the URL to disagree with it.
        Outcome outcome = AtsPageVerifier.verify(identity(
                "https://careers.airbnb.com/",
                "https://careers.airbnb.com/",
                "Careers at Airbnb",
                "Explore roles",
                noForm()), AtsPlatform.UNKNOWN);

        assertThat(outcome.status()).isEqualTo(ValidationReport.Status.INVALID_POSTING);
    }

    @Test
    @DisplayName("search page")
    void searchPageIsInvalidPosting() {
        Outcome outcome = AtsPageVerifier.verify(identity(
                "https://boards.greenhouse.io/acme",
                "https://boards.greenhouse.io/acme?q=engineer",
                "Search jobs at Acme",
                "Search jobs\n42 results",
                noForm()), AtsPlatform.GREENHOUSE);

        assertThat(outcome.valid()).isFalse();
        assertThat(outcome.status()).isEqualTo(ValidationReport.Status.INVALID_POSTING);
    }

    @Test
    @DisplayName("maintenance page")
    void maintenancePageIsUnsupported() {
        Outcome outcome = AtsPageVerifier.verify(identity(
                "https://jobs.lever.co/acme/abc/apply", "https://jobs.lever.co/acme/abc/apply",
                "Maintenance", "We'll be back shortly — scheduled maintenance in progress.",
                noForm()), AtsPlatform.LEVER);

        assertThat(outcome.status()).isEqualTo(ValidationReport.Status.UNSUPPORTED_PAGE);
    }

    @Test
    @DisplayName("unsupported domain with no form")
    void unknownPlatformWithNoFormIsUnsupported() {
        Outcome outcome = AtsPageVerifier.verify(identity(
                "https://example.com/page", "https://example.com/page",
                "Example Domain", "This domain is for use in examples.",
                noForm()), AtsPlatform.UNKNOWN);

        assertThat(outcome.status()).isEqualTo(ValidationReport.Status.UNSUPPORTED_PAGE);
    }

    @Test
    @DisplayName("right posting, page loaded, but there is simply no form on it")
    void postingPageWithoutAFormIsInvalidApplicationPage() {
        // Path keeps its posting identity, no redirect, no removal wording — the page just is not
        // a form. This is the residual bucket, and it must not be reported as one of the specific
        // reasons above.
        Outcome outcome = AtsPageVerifier.verify(identity(
                "https://jobs.lever.co/acme/abc/apply", "https://jobs.lever.co/acme/abc/apply",
                "Acme - Senior Engineer", "About the role\nResponsibilities\nBenefits",
                noForm()), AtsPlatform.LEVER);

        assertThat(outcome.status()).isEqualTo(ValidationReport.Status.INVALID_APPLICATION_PAGE);
        assertThat(outcome.reason()).contains("no application form detected");
    }

    // ── Valid pages must keep working — the expensive failure mode ──

    @Test
    @DisplayName("the real Lever page validated live in this session still passes")
    void realLeverApplicationPageIsAccepted() {
        // Measured from https://jobs.lever.co/thinkahead/94245c1a-.../apply — 32 controls,
        // "Submit application", resume upload present, no redirect.
        Outcome outcome = AtsPageVerifier.verify(identity(
                "https://jobs.lever.co/thinkahead/94245c1a-52ad-4ee6-8717-b20a58c5474a/apply",
                "https://jobs.lever.co/thinkahead/94245c1a-52ad-4ee6-8717-b20a58c5474a/apply",
                "AHEAD - Senior Software Engineer",
                "Submit your application\nResume/CV\nFull name\nEmail\nSubmit application",
                realForm()), AtsPlatform.LEVER);

        assertThat(outcome.valid()).isTrue();
        assertThat(outcome.status()).isEqualTo(ValidationReport.Status.COMPLETED);
        assertThat(outcome.signalScore()).isEqualTo(5);
    }

    @Test
    @DisplayName("Greenhouse's own boards -> job-boards migration redirect is not a rejection")
    void sameSiteMigrationRedirectIsAccepted() {
        // boards.greenhouse.io -> job-boards.greenhouse.io is Greenhouse's normal redirect. Treating
        // it as leaving the site would reject every valid Greenhouse posting.
        Outcome outcome = AtsPageVerifier.verify(identity(
                "https://boards.greenhouse.io/acme/jobs/4567",
                "https://job-boards.greenhouse.io/acme/jobs/4567",
                "Acme - Backend Engineer",
                "Apply for this job\nResume\nFirst name\nEmail",
                realForm()), AtsPlatform.GREENHOUSE);

        assertThat(outcome.valid()).isTrue();
    }

    @Test
    @DisplayName("a form on an unrecognised host is accepted — unknown ATS is not automatically bad")
    void unknownPlatformWithARealFormIsAccepted() {
        Outcome outcome = AtsPageVerifier.verify(identity(
                "https://careers.acme.com/apply/123", "https://careers.acme.com/apply/123",
                "Apply - Acme", "Submit your application\nResume", realForm()), AtsPlatform.UNKNOWN);

        assertThat(outcome.valid()).isTrue();
    }

    @Test
    @DisplayName("minimum signal score is the accept/reject boundary")
    void signalScoreThresholdIsEnforced() {
        FormSignals justUnder = new FormSignals(0, 1, 0, 0, false, false);   // score 1
        FormSignals justOver = new FormSignals(1, 1, 0, 0, false, false);    // score 2

        assertThat(justUnder.score()).isEqualTo(AtsPageVerifier.MINIMUM_SIGNAL_SCORE - 1);
        assertThat(justOver.score()).isEqualTo(AtsPageVerifier.MINIMUM_SIGNAL_SCORE);

        assertThat(AtsPageVerifier.verify(identity("https://jobs.lever.co/a/b/apply",
                "https://jobs.lever.co/a/b/apply", "Role", "text", justUnder), AtsPlatform.LEVER)
                .valid()).isFalse();
        assertThat(AtsPageVerifier.verify(identity("https://jobs.lever.co/a/b/apply",
                "https://jobs.lever.co/a/b/apply", "Role", "text", justOver), AtsPlatform.LEVER)
                .valid()).isTrue();
    }

    // ── Never break validation through its own defect ──

    @Test
    @DisplayName("malformed URLs never throw and never falsely reject")
    void malformedUrlsAreTolerated() {
        Outcome outcome = AtsPageVerifier.verify(identity(
                "not a url at all", "also :// not a url", "Apply",
                "Submit your application", realForm()), AtsPlatform.LEVER);

        assertThat(outcome.valid()).isTrue();
    }

    @Test
    @DisplayName("a missing identity snapshot proceeds rather than rejecting")
    void nullIdentityProceeds() {
        // Bias is deliberate: a wrong rejection makes validation useless, a wrong acceptance only
        // restores pre-F4 behaviour.
        Outcome outcome = AtsPageVerifier.verify(null, AtsPlatform.LEVER);

        assertThat(outcome.valid()).isTrue();
        assertThat(outcome.evidence()).anyMatch(e -> e.contains("verification skipped"));
    }

    @Test
    @DisplayName("null fields inside the snapshot are tolerated")
    void nullFieldsAreTolerated() {
        Outcome outcome = AtsPageVerifier.verify(
                new PageIdentity("https://jobs.lever.co/a/b/apply",
                        "https://jobs.lever.co/a/b/apply", null, null, null),
                AtsPlatform.LEVER);

        assertThat(outcome).isNotNull();
        assertThat(outcome.valid()).isFalse();
        assertThat(outcome.status()).isEqualTo(ValidationReport.Status.INVALID_APPLICATION_PAGE);
    }

    // ── Redirect analysis ──

    @Test
    void redirectAnalysisDetectsLostPostingIdentity() {
        var r = AtsPageVerifier.analyseRedirect(
                "https://boards.greenhouse.io/gitlab/jobs/12345",
                "https://job-boards.greenhouse.io/gitlab?error=true");

        assertThat(r.lostPostingIdentity()).isTrue();
        assertThat(r.errorFlagged()).isTrue();
        assertThat(r.sameRegistrableSite()).isTrue();
    }

    @Test
    void redirectAnalysisKeepsPostingIdentityWhenPathIsPreserved() {
        var r = AtsPageVerifier.analyseRedirect(
                "https://boards.greenhouse.io/acme/jobs/1",
                "https://job-boards.greenhouse.io/acme/jobs/1");

        assertThat(r.lostPostingIdentity()).isFalse();
        assertThat(r.errorFlagged()).isFalse();
    }

    @Test
    void requestingABoardIndexIsNotALostIdentity() {
        var r = AtsPageVerifier.analyseRedirect(
                "https://boards.greenhouse.io/acme", "https://job-boards.greenhouse.io/acme");

        assertThat(r.lostPostingIdentity()).isFalse();
    }
}
