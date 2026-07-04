package ai.careerpilot.execution.safety;

import ai.careerpilot.domain.*;
import ai.careerpilot.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 2E.5 — the safety engine is the primary defense against submitting a bad application, so
 * every check is exercised independently against a fully-passing baseline: flipping one input at a
 * time must drive exactly the expected SAFE / REVIEW / BLOCK verdict.
 */
class SafetyEngineTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID pkgId = UUID.randomUUID();

    private ApplicationPackageRepository packages;
    private JobRepository jobs;
    private ResumeTailoringRepository tailorings;
    private ResumeAtsAnalysisRepository atsAnalyses;
    private ResumeGapAnalysisRepository gapAnalyses;
    private CoverLetterRepository coverLetters;
    private ApplicationRepository applications;
    private CandidatePreferencesRepository preferences;

    @BeforeEach
    void setUp() {
        packages = mock(ApplicationPackageRepository.class);
        jobs = mock(JobRepository.class);
        tailorings = mock(ResumeTailoringRepository.class);
        atsAnalyses = mock(ResumeAtsAnalysisRepository.class);
        gapAnalyses = mock(ResumeGapAnalysisRepository.class);
        coverLetters = mock(CoverLetterRepository.class);
        applications = mock(ApplicationRepository.class);
        preferences = mock(CandidatePreferencesRepository.class);
        baselineAllPass();
    }

    /** Wire every dependency to its "safe" answer; individual tests override one at a time. */
    private void baselineAllPass() {
        ApplicationPackage pkg = ApplicationPackage.builder()
                .id(pkgId).userId(userId).jobId(jobId).status(ApplicationPackage.STATUS_ASSEMBLED).build();
        when(packages.findById(pkgId)).thenReturn(Optional.of(pkg));
        when(packages.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.of(pkg));

        Job job = Job.builder().id(jobId).title("Senior Java Engineer").company("Acme").country("us").build();
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));

        when(tailorings.findFirstByUserIdAndJobIdOrderByTailoringVersionDesc(userId, jobId))
                .thenReturn(Optional.of(ResumeTailoring.builder().build()));
        when(coverLetters.findByUserIdAndJobId(userId, jobId))
                .thenReturn(Optional.of(CoverLetter.builder().build()));
        when(atsAnalyses.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId))
                .thenReturn(Optional.of(ResumeAtsAnalysis.builder().atsScore(85).build()));
        when(gapAnalyses.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId))
                .thenReturn(Optional.of(ResumeGapAnalysis.builder().gapScore(20).build()));
        when(applications.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId))
                .thenReturn(Optional.empty());
        when(preferences.findById(userId)).thenReturn(Optional.empty());
    }

    private SafetyEngine engine(boolean enabled) {
        return new SafetyEngine(packages, jobs, tailorings, atsAnalyses, gapAnalyses, coverLetters,
                applications, preferences, new SafetyMetrics(), enabled, 60, 60, "evilcorp,scamco");
    }

    private SafetyResult evaluate() {
        return engine(true).evaluate(userId, jobId, pkgId);
    }

    // ── happy path ──

    @Test
    void allChecksPassingIsSafe() {
        assertThat(evaluate().verdict()).isEqualTo(SafetyVerdict.SAFE);
    }

    // ── disabled gate is a closed gate ──

    @Test
    void disabledEngineBlocks() {
        SafetyResult r = engine(false).evaluate(userId, jobId, pkgId);
        assertThat(r.verdict()).isEqualTo(SafetyVerdict.BLOCK);
        assertThat(r.reasonSummary()).contains("disabled");
    }

    // ── hard BLOCK checks ──

    @Test
    void missingPackageBlocks() {
        when(packages.findById(pkgId)).thenReturn(Optional.empty());
        assertThat(evaluate().verdict()).isEqualTo(SafetyVerdict.BLOCK);
    }

    @Test
    void unassembledPackageBlocks() {
        ApplicationPackage pkg = ApplicationPackage.builder()
                .id(pkgId).userId(userId).jobId(jobId).status(ApplicationPackage.STATUS_INCOMPLETE).build();
        when(packages.findById(pkgId)).thenReturn(Optional.of(pkg));
        assertThat(evaluate().verdict()).isEqualTo(SafetyVerdict.BLOCK);
    }

    @Test
    void missingTailoredResumeBlocks() {
        when(tailorings.findFirstByUserIdAndJobIdOrderByTailoringVersionDesc(userId, jobId))
                .thenReturn(Optional.empty());
        assertThat(evaluate().verdict()).isEqualTo(SafetyVerdict.BLOCK);
    }

    @Test
    void missingCoverLetterBlocks() {
        when(coverLetters.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.empty());
        assertThat(evaluate().verdict()).isEqualTo(SafetyVerdict.BLOCK);
    }

    @Test
    void duplicateAppliedApplicationBlocks() {
        when(applications.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId))
                .thenReturn(Optional.of(Application.builder().status("APPLIED").build()));
        SafetyResult r = evaluate();
        assertThat(r.verdict()).isEqualTo(SafetyVerdict.BLOCK);
        assertThat(r.reasonSummary()).contains("duplicate");
    }

    @Test
    void savedButNotSubmittedApplicationIsNotADuplicate() {
        when(applications.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId))
                .thenReturn(Optional.of(Application.builder().status("SAVED").build()));
        assertThat(evaluate().verdict()).isEqualTo(SafetyVerdict.SAFE);
    }

    @Test
    void excludedRoleBlocks() {
        when(preferences.findById(userId)).thenReturn(Optional.of(
                CandidatePreferences.builder().excludedRoles("sales, recruiter, java").build()));
        // title "Senior Java Engineer" contains "java"
        SafetyResult r = evaluate();
        assertThat(r.verdict()).isEqualTo(SafetyVerdict.BLOCK);
        assertThat(r.reasonSummary()).contains("excluded role");
    }

    @Test
    void blacklistedCompanyBlocks() {
        when(jobs.findById(jobId)).thenReturn(Optional.of(
                Job.builder().id(jobId).title("Engineer").company("EvilCorp").country("us").build()));
        SafetyResult r = evaluate();
        assertThat(r.verdict()).isEqualTo(SafetyVerdict.BLOCK);
        assertThat(r.reasonSummary()).contains("blacklisted");
    }

    // ── soft REVIEW checks ──

    @Test
    void lowAtsScoreIsReview() {
        when(atsAnalyses.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId))
                .thenReturn(Optional.of(ResumeAtsAnalysis.builder().atsScore(40).build()));
        assertThat(evaluate().verdict()).isEqualTo(SafetyVerdict.REVIEW);
    }

    @Test
    void missingAtsAnalysisIsReview() {
        when(atsAnalyses.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId))
                .thenReturn(Optional.empty());
        assertThat(evaluate().verdict()).isEqualTo(SafetyVerdict.REVIEW);
    }

    @Test
    void largeGapIsReview() {
        when(gapAnalyses.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId))
                .thenReturn(Optional.of(ResumeGapAnalysis.builder().gapScore(90).build()));
        assertThat(evaluate().verdict()).isEqualTo(SafetyVerdict.REVIEW);
    }

    @Test
    void missingGapAnalysisIsReview() {
        when(gapAnalyses.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId))
                .thenReturn(Optional.empty());
        assertThat(evaluate().verdict()).isEqualTo(SafetyVerdict.REVIEW);
    }

    @Test
    void offPreferenceCountryIsReview() {
        when(preferences.findById(userId)).thenReturn(Optional.of(
                CandidatePreferences.builder().preferredCountries("in,de").build()));
        // job country "us" not in {in, de}
        assertThat(evaluate().verdict()).isEqualTo(SafetyVerdict.REVIEW);
    }

    // ── precedence + resilience ──

    @Test
    void blockBeatsReview() {
        // low ATS (REVIEW) AND missing cover letter (BLOCK) -> BLOCK
        when(atsAnalyses.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId))
                .thenReturn(Optional.of(ResumeAtsAnalysis.builder().atsScore(10).build()));
        when(coverLetters.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.empty());
        assertThat(evaluate().verdict()).isEqualTo(SafetyVerdict.BLOCK);
    }

    @Test
    void anExceptionFailsClosedToBlock() {
        when(packages.findById(any())).thenThrow(new RuntimeException("db down"));
        assertThat(evaluate().verdict()).isEqualTo(SafetyVerdict.BLOCK);
    }
}
