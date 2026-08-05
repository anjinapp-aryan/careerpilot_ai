package ai.careerpilot.execution.verification.evidence;

import ai.careerpilot.execution.verification.VerificationResult;
import org.springframework.stereotype.Service;

/**
 * Phase 0 — the shared guest-apply verification path: analyze the captured post-submit page,
 * adjudicate the resulting evidence, and express the verdict as the {@link VerificationResult}
 * that {@code ATSConnector.verifySubmission} already returns.
 *
 * <p>Exists so {@code GreenhouseConnector} and {@code LeverConnector} share one implementation
 * instead of the identical copy-pasted heuristic they carried before, and so any future
 * guest-apply connector inherits the same honest rule for free.
 *
 * <p>The {@link VerificationResult#method()} it produces embeds the adjudicated {@link
 * ConfidenceLevel}, so the durable evidence columns on {@code application_execution} record not
 * just <em>whether</em> we believed the submission but <em>how strongly</em> — with no schema
 * change (the existing {@code verification_method VARCHAR(64)} column is more than wide enough).
 */
@Service
public class ConfirmationPageVerifier {

    static final String METHOD_PREFIX = "EVIDENCE_ADJUDICATION:";

    private final ConfirmationPageAnalyzer analyzer;
    private final VerificationAdjudicator adjudicator;

    public ConfirmationPageVerifier(ConfirmationPageAnalyzer analyzer, VerificationAdjudicator adjudicator) {
        this.analyzer = analyzer;
        this.adjudicator = adjudicator;
    }

    public VerificationResult verify(String capturedPageContent) {
        EvidenceBundle bundle = analyzer.analyze(capturedPageContent);
        ConfidenceLevel level = adjudicator.adjudicate(bundle);
        String method = METHOD_PREFIX + level.name();
        String reason = adjudicator.explain(bundle, level);

        if (level.permitsSubmittedStatus()) {
            return VerificationResult.verified(method, reason);
        }
        if (bundle.has(SignalType.ERROR_STATE)) {
            // A positively-detected failure is a different claim from "we couldn't tell".
            return VerificationResult.notVerified(method, reason);
        }
        return VerificationResult.unableToVerify(method, reason);
    }
}
