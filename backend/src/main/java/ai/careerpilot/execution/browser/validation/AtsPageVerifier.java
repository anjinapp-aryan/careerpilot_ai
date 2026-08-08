package ai.careerpilot.execution.browser.validation;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Phase F4 — proves the page actually loaded is an ATS <b>application form</b> before any discovery
 * runs against it.
 *
 * <h2>The production bug this closes</h2>
 * {@code https://boards.greenhouse.io/gitlab/jobs/12345} is not a real posting. Greenhouse does not
 * answer it with a 404 — measured, it answers <b>HTTP 200</b> after two redirects, landing on
 * {@code https://job-boards.greenhouse.io/gitlab?error=true} with the title "Jobs at GitLab": the
 * company's board index. Navigation therefore succeeded, discovery ran happily against a board
 * index, and the harness reported {@code COMPLETED} with a confidence score. A number computed from
 * a page that was never an application form is worse than no number, because an operator will act
 * on it.
 *
 * <h2>Why the strongest rule is structural, not textual</h2>
 * The decisive signal is that the requested URL <b>identified a specific posting and the final URL
 * no longer does</b>. That is a fact about the redirect, not a guess about wording, so it survives
 * copy changes, localisation and A/B tests. Text rules are the second tier, and they run against
 * {@code document.body.innerText} rather than raw HTML for a concrete measured reason: the literal
 * string {@code 404} appears inside asset filenames on GitLab's perfectly ordinary board index, so
 * an HTML substring match would have "detected" a removed job on a page that has none.
 *
 * <h2>Discipline</h2>
 * Pure and deterministic — no I/O, no browser handle, no LLM. Everything it needs arrives in a
 * {@link PageIdentity} snapshot, the same shape as {@code VerificationAdjudicator} taking an
 * {@code EvidenceBundle} and {@code AutomationConfidence} taking a {@code SelectorCoverage}. That is
 * what makes every rule below unit-testable without launching Chromium.
 *
 * <h2>Bias</h2>
 * Deliberately asymmetric. A wrong rejection makes validation useless; a wrong acceptance merely
 * leaves the pre-F4 behaviour. So every rule fires only on positive evidence of a <em>problem</em>,
 * and an unreadable/empty snapshot is {@link Outcome#valid} rather than a rejection.
 */
public final class AtsPageVerifier {

    private AtsPageVerifier() {}

    /**
     * What the page turned out to be, captured after navigation and {@code waitForStable}.
     *
     * @param requestedUrl the URL the operator asked for
     * @param finalUrl     the URL actually resting in the address bar after redirects
     * @param title        {@code document.title}
     * @param visibleText  {@code document.body.innerText} — visible text only, never raw HTML
     * @param signals      structural form signals counted in the page (see {@code FormDiscoveryScript})
     */
    public record PageIdentity(String requestedUrl, String finalUrl, String title,
                               String visibleText, FormSignals signals) {

        public PageIdentity {
            title = title == null ? "" : title;
            visibleText = visibleText == null ? "" : visibleText;
            signals = signals == null ? FormSignals.none() : signals;
        }
    }

    /**
     * Structural evidence that an application form exists, counted from the DOM rather than inferred
     * from prose. Counts, not booleans, so a page with one stray input is distinguishable from a
     * real form.
     */
    public record FormSignals(int fileInputs, int emailInputs, int textInputs,
                              int submitButtons, boolean applicationHeading, boolean passwordInputs) {

        public static FormSignals none() {
            return new FormSignals(0, 0, 0, 0, false, false);
        }

        /**
         * How much this page looks like an application form, 0-5. One point each for the things a
         * real application always has. A password field is not scored here — it is handled as a
         * login rejection, because a page asking for a password is asking to authenticate, not to
         * receive an application.
         */
        public int score() {
            int s = 0;
            if (fileInputs > 0) s++;          // resume upload
            if (emailInputs > 0) s++;         // email
            if (textInputs >= 2) s++;         // name and friends
            if (submitButtons > 0) s++;       // something to press
            if (applicationHeading) s++;      // "Apply for this job" / "Submit your application"
            return s;
        }

        public Map<String, Object> snapshot() {
            return Map.of("fileInputs", fileInputs, "emailInputs", emailInputs,
                    "textInputs", textInputs, "submitButtons", submitButtons,
                    "applicationHeading", applicationHeading, "score", score());
        }
    }

    /** The verdict. {@code valid} means discovery may proceed; anything else means stop. */
    public record Outcome(boolean valid, ValidationReport.Status status, String reason,
                          List<String> evidence, int signalScore) {

        public Outcome {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }

        public static Outcome valid(int score, List<String> evidence) {
            return new Outcome(true, ValidationReport.Status.COMPLETED,
                    "page verified as an ATS application form", evidence, score);
        }

        public static Outcome reject(ValidationReport.Status status, String reason,
                                     List<String> evidence, int score) {
            return new Outcome(false, status, reason, evidence, score);
        }
    }

    /** Minimum {@link FormSignals#score()} for a page to be treated as an application form. */
    public static final int MINIMUM_SIGNAL_SCORE = 2;

    // Phrase-anchored, not bare tokens. "404" alone matches asset filenames; "error 404" does not.
    private static final List<String> JOB_REMOVED_PHRASES = List.of(
            "job not found", "position not found", "posting not found", "page not found",
            "error 404", "404 error", "no longer available", "no longer accepting",
            "position closed", "this job is closed", "posting has expired", "job has expired",
            "this posting is no longer", "position has been filled", "job has been filled",
            "we are no longer accepting applications", "this role is no longer open");

    private static final List<String> LOGIN_PHRASES = List.of(
            "sign in to continue", "please sign in", "please log in", "log in to continue",
            "you must be signed in", "access denied", "you do not have permission",
            "unauthorized access", "session expired", "authentication required");

    private static final List<String> MAINTENANCE_PHRASES = List.of(
            "under maintenance", "scheduled maintenance", "temporarily unavailable",
            "service unavailable", "we'll be back", "we will be back shortly");

    private static final List<String> INDEX_TITLE_PHRASES = List.of(
            "jobs at ", "careers at ", "open positions", "current openings",
            "job board", "all jobs", "search jobs", "job search");

    /** A path segment that identifies one specific posting. */
    private static final Pattern POSTING_IDENTITY = Pattern.compile(
            "/(jobs?|positions?|opportunit(y|ies)|postings?|openings?|apply|application)(/|$)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Verify one page. Never throws — an unusable snapshot degrades to {@link Outcome#valid} so a
     * defect in this class can never take validation offline.
     *
     * <p>Rules are ordered most-specific first, and the first match wins, so a removed job is
     * reported as {@code JOB_REMOVED} rather than the vaguer {@code INVALID_APPLICATION_PAGE} it
     * would also satisfy. Which reason an operator sees is the whole value of this layer.
     */
    public static Outcome verify(PageIdentity identity, AtsPlatform platform) {
        if (identity == null) {
            return Outcome.valid(0, List.of("no page identity captured — verification skipped"));
        }
        try {
            return evaluate(identity, platform);
        } catch (Exception e) {
            return Outcome.valid(0, List.of("verification error, proceeding: " + e));
        }
    }

    private static Outcome evaluate(PageIdentity identity, AtsPlatform platform) {
        String text = identity.visibleText().toLowerCase(Locale.ROOT);
        String title = identity.title().toLowerCase(Locale.ROOT);
        int score = identity.signals().score();
        List<String> evidence = new ArrayList<>();
        evidence.add("signalScore=" + score + "/5 " + identity.signals().snapshot());

        // 1. The job is gone. Most specific and most actionable: re-applying will never work.
        String removed = firstMatch(text, title, JOB_REMOVED_PHRASES);
        if (removed != null) {
            evidence.add("matched removal phrase: \"" + removed + "\"");
            return Outcome.reject(ValidationReport.Status.JOB_REMOVED,
                    "the posting no longer exists or is closed (page says \"" + removed + "\")",
                    evidence, score);
        }

        // 2. Authentication wall. A password field is structural proof, checked alongside the
        //    phrases so a login page with no familiar wording is still caught.
        String login = firstMatch(text, title, LOGIN_PHRASES);
        // A password field alone is not proof — some ATSes offer "create an account" beside the
        // form. What settles it is the absence of the two things only a real application has: a
        // resume upload and an apply/submit-application heading. An email+password+submit login page
        // otherwise scores 2 and would slip through the generic threshold below.
        boolean bareLoginForm = identity.signals().passwordInputs()
                && identity.signals().fileInputs() == 0
                && !identity.signals().applicationHeading();
        if (login != null || bareLoginForm) {
            evidence.add(login != null ? "matched login phrase: \"" + login + "\""
                    : "password input present with no resume upload and no application heading");
            return Outcome.reject(ValidationReport.Status.LOGIN_REQUIRED,
                    "the page requires sign-in — an application form was not reachable",
                    evidence, score);
        }

        String maintenance = firstMatch(text, title, MAINTENANCE_PHRASES);
        if (maintenance != null) {
            evidence.add("matched maintenance phrase: \"" + maintenance + "\"");
            return Outcome.reject(ValidationReport.Status.UNSUPPORTED_PAGE,
                    "the site is unavailable or under maintenance (page says \"" + maintenance + "\")",
                    evidence, score);
        }

        // 3. Redirect analysis — the rule that actually catches the reported bug, and the only one
        //    that does not depend on wording.
        Redirect redirect = analyseRedirect(identity.requestedUrl(), identity.finalUrl());
        if (redirect.hostChanged() && !redirect.sameRegistrableSite()) {
            evidence.add("host changed " + redirect.fromHost() + " -> " + redirect.toHost());
            return Outcome.reject(ValidationReport.Status.REDIRECTED_TO_NON_APPLICATION,
                    "the URL redirected to a different site (" + redirect.fromHost()
                            + " -> " + redirect.toHost() + ")", evidence, score);
        }
        if (redirect.lostPostingIdentity()) {
            evidence.add("path lost its posting identity: " + redirect.fromPath()
                    + " -> " + redirect.toPath());
            if (redirect.errorFlagged()) {
                evidence.add("final URL carries an error flag: " + redirect.toQuery());
            }
            return Outcome.reject(ValidationReport.Status.INVALID_POSTING,
                    "the posting does not exist — " + platformLabel(platform)
                            + " redirected away from the job to a board page ("
                            + redirect.toPath() + (redirect.errorFlagged() ? "?" + redirect.toQuery() : "")
                            + ")", evidence, score);
        }
        if (redirect.errorFlagged()) {
            evidence.add("final URL carries an error flag: " + redirect.toQuery());
            return Outcome.reject(ValidationReport.Status.INVALID_POSTING,
                    "the ATS reported an error for this posting (" + redirect.toQuery() + ")",
                    evidence, score);
        }

        // 4. A board index or search page that was requested directly — no redirect to detect, so
        //    this is caught by what the page is rather than how it was reached.
        //
        //    Guarded by the URL still naming a specific posting, because company careers portals
        //    routinely suffix a real job title with their board's name. Measured: Airbnb serves
        //    "Senior Principal, Competitive Intelligence - Careers at Airbnb" at
        //    /positions/8053756 — a genuine posting that the title rule alone called a board index,
        //    reporting the wrong reason for a page whose actual problem was an iframe-hosted form.
        String indexTitle = firstMatch("", title, INDEX_TITLE_PHRASES);
        boolean urlNamesAPosting = POSTING_IDENTITY.matcher(
                path(parse(identity.finalUrl()))).find();
        if (indexTitle != null && !urlNamesAPosting && score < MINIMUM_SIGNAL_SCORE) {
            evidence.add("title looks like a board index: \"" + identity.title() + "\"");
            return Outcome.reject(ValidationReport.Status.INVALID_POSTING,
                    "this is a job board or search page, not an application form (title \""
                            + identity.title() + "\")", evidence, score);
        }

        // 5. Unknown host. Last, not first — a recognised failure above is a better answer than
        //    "unsupported", and ValidationUrlPolicy has already refused genuinely disallowed hosts.
        if (platform == null || platform == AtsPlatform.UNKNOWN) {
            evidence.add("host is not a recognised ATS");
            if (score < MINIMUM_SIGNAL_SCORE) {
                return Outcome.reject(ValidationReport.Status.UNSUPPORTED_PAGE,
                        "the page is not a recognised ATS and shows no application form signals",
                        evidence, score);
            }
        }

        // 6. Nothing specific was wrong, but the page simply is not a form.
        if (score < MINIMUM_SIGNAL_SCORE) {
            return Outcome.reject(ValidationReport.Status.INVALID_APPLICATION_PAGE,
                    "no application form detected on the page (signal score " + score + "/5, "
                            + MINIMUM_SIGNAL_SCORE + " required)", evidence, score);
        }

        return Outcome.valid(score, evidence);
    }

    private static String platformLabel(AtsPlatform platform) {
        return platform == null || platform == AtsPlatform.UNKNOWN ? "the site"
                : platform.name().charAt(0) + platform.name().substring(1).toLowerCase(Locale.ROOT);
    }

    private static String firstMatch(String text, String title, List<String> phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase) || title.contains(phrase)) return phrase;
        }
        return null;
    }

    // ── Redirect analysis ──

    record Redirect(String fromHost, String toHost, String fromPath, String toPath, String toQuery,
                    boolean hostChanged, boolean sameRegistrableSite,
                    boolean lostPostingIdentity, boolean errorFlagged) {
    }

    /**
     * Compare requested and final URL.
     *
     * <p>{@code boards.greenhouse.io -> job-boards.greenhouse.io} is Greenhouse's own normal
     * migration redirect and must not be treated as leaving the site, so hosts are compared at the
     * registrable-domain level rather than exactly. What matters is not that the host moved but that
     * {@code /gitlab/jobs/12345} became {@code /gitlab}: the posting identity was dropped.
     */
    static Redirect analyseRedirect(String requestedUrl, String finalUrl) {
        URI from = parse(requestedUrl);
        URI to = parse(finalUrl);
        if (from == null || to == null) {
            return new Redirect(host(from), host(to), path(from), path(to), query(to),
                    false, true, false, false);
        }
        String fromHost = host(from);
        String toHost = host(to);
        String fromPath = path(from);
        String toPath = path(to);
        boolean hostChanged = !fromHost.equalsIgnoreCase(toHost);
        boolean sameSite = registrable(fromHost).equalsIgnoreCase(registrable(toHost));

        // Identity is "lost" only when the request genuinely named a posting and the destination no
        // longer does. A requested board index landing on a board index is not a redirect problem.
        boolean requestedPosting = POSTING_IDENTITY.matcher(fromPath).find();
        boolean landedOnPosting = POSTING_IDENTITY.matcher(toPath).find();
        boolean lost = requestedPosting && !landedOnPosting;

        String query = query(to);
        boolean errorFlagged = query.toLowerCase(Locale.ROOT).matches(
                ".*(^|&)(error|not_found|notfound)=(true|1|yes).*");

        return new Redirect(fromHost, toHost, fromPath, toPath, query,
                hostChanged, sameSite, lost, errorFlagged);
    }

    private static URI parse(String url) {
        try {
            return url == null || url.isBlank() ? null : URI.create(url.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String host(URI uri) {
        return uri == null || uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    }

    private static String path(URI uri) {
        String p = uri == null || uri.getPath() == null ? "" : uri.getPath();
        return p.isEmpty() ? "/" : p;
    }

    private static String query(URI uri) {
        return uri == null || uri.getQuery() == null ? "" : uri.getQuery();
    }

    /** Last two labels — enough to tell "same company's ATS" from "somewhere else entirely". */
    private static String registrable(String host) {
        String[] parts = host.split("\\.");
        return parts.length < 2 ? host : parts[parts.length - 2] + "." + parts[parts.length - 1];
    }
}
