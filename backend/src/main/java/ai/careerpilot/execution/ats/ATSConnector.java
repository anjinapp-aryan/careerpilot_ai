package ai.careerpilot.execution.ats;

import ai.careerpilot.domain.Job;
import ai.careerpilot.execution.verification.VerificationResult;

import java.util.Map;

/**
 * Phase 2E.3 — one connector per Applicant Tracking System (Greenhouse, Lever, Workday, …). Same
 * "joins the chain only if configured" convention as {@code LlmProvider} / {@code JobProvider}:
 * {@link ATSConnectorRegistry} only ever hands work to a connector whose {@link #isConfigured()} is
 * true. HIGH RISK — every connector in the 2E build is an unconfigured stub whose {@link #submit}
 * throws, so no application is ever submitted through this framework.
 */
public interface ATSConnector {

    /** Stable connector name for audit/attribution (e.g. "greenhouse"). */
    String name();

    /** True only when credentials/API access are wired AND the connector flag is on. False in the 2E build. */
    boolean isConfigured();

    /** True if this connector recognises the job's source/URL as its own ATS (used for routing). */
    boolean detect(Job job);

    /** Establish an authenticated session with the ATS. */
    void authenticate(Map<String, String> credentials);

    /** Extract the target application form's fields (field-name -> label/type) for the job. */
    Map<String, String> extractForm(Job job);

    /** Submit the application. Returns the ATS confirmation reference. */
    String submit(Job job, Map<String, String> answers);

    /** Fetch the current ATS-side status for a previously submitted application. */
    String track(String confirmationReference);

    /**
     * Phase 7.16.1 — verify a previously-"submitted" application actually reached the ATS. Never
     * assumes success: implementations must return {@link ai.careerpilot.execution.verification.VerificationStatus#UNABLE_TO_VERIFY}
     * (not fabricate {@code VERIFIED}) whenever they lack real evidence to check. See
     * {@link ai.careerpilot.execution.ats.AbstractStubConnector#verifySubmission} for the honest
     * default every unconfigured connector inherits.
     */
    VerificationResult verifySubmission(String confirmationReference);
}
