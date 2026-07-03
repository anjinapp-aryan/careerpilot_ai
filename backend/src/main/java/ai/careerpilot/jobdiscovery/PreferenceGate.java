package ai.careerpilot.jobdiscovery;

import ai.careerpilot.domain.Job;
import org.springframework.stereotype.Component;

/**
 * Phase 2B-5 (Step 6) — mandatory preference rejects. Today {@link JobScoring#scoreV2} treats
 * work-mode/location/visa as <i>soft</i> weights (1–10% of the score each), so a job that
 * structurally cannot work for a candidate (e.g. onsite-only when they set "Remote only") can
 * still clear the Recommended gate on strong skill overlap alone — and, since Phase 2B-1, could
 * even land in {@link JobCategory#AUTO_APPLY}. This gate rejects those jobs outright, before
 * scoring, when {@code jobs.matching.hard-preference-enabled} is on.
 *
 * <p>Reuses {@link JobScoring#locationMatches} / {@link JobScoring#remotePrefMatches} (package-
 * private) so the hard gate and the soft-scoring weight can never disagree on what "matches" means.
 *
 * <p><b>Only rejects on an explicit signal.</b> A preference the candidate never set, or a job
 * field the provider never populated (null), never causes a reject — mirroring the "no signal →
 * don't penalize" philosophy already used throughout {@code scoreV2}. Salary is deliberately
 * <i>not</i> gated here: provider salary data is sparse and currency-inconsistent, making a hard
 * reject on it too risky for v1 (stays a soft weight; documented follow-up).
 */
@Component
public class PreferenceGate {

    private final JobScoring scoring;

    public PreferenceGate(JobScoring scoring) {
        this.scoring = scoring;
    }

    /** True when this job structurally violates one of the candidate's stated mandatory preferences. */
    public boolean isHardRejected(Job job, JobScoring.PreferenceContext p) {
        if (p == null) return false;

        // Work-mode: only reject when the candidate picked at least one mode AND the job's mode is
        // known AND it isn't one the candidate accepts. An unset preference or an unknown job mode
        // never rejects.
        if (p.anyRemotePref() && job.getRemoteType() != null
                && !scoring.remotePrefMatches(job.getRemoteType(), p)) {
            return true;
        }

        // Location: only reject when the candidate named countries/cities, the job is NOT remote
        // (remote already satisfies any location preference, same convention as soft scoring), and
        // the job matches none of them.
        boolean jobRemote = "REMOTE".equals(job.getRemoteType()) || Boolean.TRUE.equals(job.getRemote());
        if (p.anyLocationPref() && !jobRemote && !scoring.locationMatches(job, p)) {
            return true;
        }

        // Visa: only reject on an EXPLICIT "no sponsorship" signal (Boolean.FALSE) — null (unknown)
        // never rejects, matching scoreV2's neutral-40 treatment of unknown sponsorship.
        if (p.visaRequired() && Boolean.FALSE.equals(job.getSponsorshipAvailable())) {
            return true;
        }

        return false;
    }
}
