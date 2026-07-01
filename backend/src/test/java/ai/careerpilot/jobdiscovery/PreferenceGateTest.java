package ai.careerpilot.jobdiscovery;

import ai.careerpilot.domain.Job;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2B-5 — pins the exact spec scenario ("Remote only" rejects "Onsite Germany" even at a
 * perfect skill match) plus the "no explicit signal never rejects" guarantee that makes the hard
 * gate safe to turn on (it can only ever narrow, never surprise-narrow, the candidate pool).
 */
class PreferenceGateTest {

    private final PreferenceGate gate = new PreferenceGate(new JobScoring(new JobTaxonomy()));

    private static Job onsiteGermany() {
        return Job.builder().title("Architect").remoteType("ONSITE").country("Germany")
                .location("Berlin, Germany").build();
    }

    private static Job remoteJob() {
        return Job.builder().title("Architect").remoteType("REMOTE").remote(true).build();
    }

    @Test
    void remoteOnlyRejectsOnsiteJobEvenAtPerfectMatch() {
        JobScoring.PreferenceContext remoteOnly = new JobScoring.PreferenceContext(
                List.of(), List.of(), true, false, false, false, null, null);
        assertTrue(gate.isHardRejected(onsiteGermany(), remoteOnly));
    }

    @Test
    void remoteOnlyAcceptsRemoteJob() {
        JobScoring.PreferenceContext remoteOnly = new JobScoring.PreferenceContext(
                List.of(), List.of(), true, false, false, false, null, null);
        assertFalse(gate.isHardRejected(remoteJob(), remoteOnly));
    }

    @Test
    void noWorkModePreferenceNeverRejectsOnWorkMode() {
        JobScoring.PreferenceContext none = JobScoring.PreferenceContext.empty();
        assertFalse(gate.isHardRejected(onsiteGermany(), none));
    }

    @Test
    void unknownJobRemoteTypeNeverRejects() {
        JobScoring.PreferenceContext remoteOnly = new JobScoring.PreferenceContext(
                List.of(), List.of(), true, false, false, false, null, null);
        Job unknownMode = Job.builder().title("Architect").build(); // remoteType null
        assertFalse(gate.isHardRejected(unknownMode, remoteOnly));
    }

    @Test
    void countryPreferenceRejectsNonMatchingOnsiteJob() {
        JobScoring.PreferenceContext franceOnly = new JobScoring.PreferenceContext(
                List.of("France"), List.of(), false, false, false, false, null, null);
        assertTrue(gate.isHardRejected(onsiteGermany(), franceOnly));
    }

    @Test
    void countryPreferenceNeverRejectsARemoteJobRegardlessOfCountry() {
        JobScoring.PreferenceContext franceOnly = new JobScoring.PreferenceContext(
                List.of("France"), List.of(), false, false, false, false, null, null);
        assertFalse(gate.isHardRejected(remoteJob(), franceOnly));
    }

    @Test
    void visaRequiredRejectsExplicitNoSponsorship() {
        JobScoring.PreferenceContext visaRequired = new JobScoring.PreferenceContext(
                List.of(), List.of(), false, false, false, true, null, null);
        Job noSponsorship = Job.builder().title("Architect").sponsorshipAvailable(false).build();
        assertTrue(gate.isHardRejected(noSponsorship, visaRequired));
    }

    @Test
    void visaRequiredNeverRejectsUnknownSponsorship() {
        JobScoring.PreferenceContext visaRequired = new JobScoring.PreferenceContext(
                List.of(), List.of(), false, false, false, true, null, null);
        Job unknownSponsorship = Job.builder().title("Architect").build(); // sponsorshipAvailable null
        assertFalse(gate.isHardRejected(unknownSponsorship, visaRequired));
    }

    @Test
    void nullPreferencesNeverRejects() {
        assertFalse(gate.isHardRejected(onsiteGermany(), null));
    }
}
