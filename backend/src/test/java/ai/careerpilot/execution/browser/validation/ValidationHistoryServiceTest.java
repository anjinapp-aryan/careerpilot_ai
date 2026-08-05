package ai.careerpilot.execution.browser.validation;

import ai.careerpilot.domain.AtsValidationRun;
import ai.careerpilot.repo.AtsValidationRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Phase 13A — durable validation history, the campaign dashboard, and the PII boundary. */
class ValidationHistoryServiceTest {

    private static final String URL = "https://boards.greenhouse.io/acme/jobs/1";
    private final UUID userId = UUID.randomUUID();

    private AtsValidationRunRepository repo;
    private ValidationHistoryService service;

    @BeforeEach
    void setUp() {
        repo = mock(AtsValidationRunRepository.class);
        when(repo.findTop20ByUrlHashOrderByCreatedAtDesc(anyString())).thenReturn(List.of());
        when(repo.findTop50ByAtsPlatformOrderByCreatedAtDesc(anyString())).thenReturn(List.of());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new ValidationHistoryService(repo, new SelectorDriftDetector(10, 2), true);
    }

    private static ValidationReport report(int confidence, boolean ready) {
        SelectorCoverage coverage = new SelectorCoverage(20, 20, 20, 0, 0, 4, 20, 0, Map.of());
        return new ValidationReport(URL, AtsPlatform.GREENHOUSE, ValidationReport.Status.COMPLETED,
                "ok", Instant.now(), 1200, 400, 300, 100, List.of(), coverage,
                new AutomationConfidence(confidence, ready ? AutomationConfidence.Band.HIGH
                        : AutomationConfidence.Band.LOW, ready, "because"),
                ValidationReport.PageEnvironment.unknown(), "key.png", List.of());
    }

    private static AtsValidationRun stored(int confidence, boolean ready) {
        return AtsValidationRun.builder()
                .atsPlatform("GREENHOUSE").url(URL).urlHash(ValidationHistoryService.hash(URL))
                .status(ValidationReport.Status.COMPLETED.name())
                .confidenceScore(confidence).confidenceBand("HIGH").ready(ready)
                .totalControls(20).fillableControls(20).supportedControls(20)
                .unsupportedControls(0).unknownControls(0).requiredControls(4)
                .mappedControls(20).missingRequiredValues(0)
                .navigationMs(0L).discoveryMs(0L).planningMs(0L).totalMs(1000L)
                .iframeCount(0).shadowRootCount(0).captchaDetected(false).consoleErrorCount(0)
                .createdAt(Instant.now())
                .build();
    }

    // ── persistence ──

    @Test
    void aRunIsPersistedWithItsFullCoverageAndConfidence() {
        service.record(report(96, true), userId);

        ArgumentCaptor<AtsValidationRun> captor = ArgumentCaptor.forClass(AtsValidationRun.class);
        verify(repo).save(captor.capture());
        AtsValidationRun saved = captor.getValue();

        assertThat(saved.getAtsPlatform()).isEqualTo("GREENHOUSE");
        assertThat(saved.getConfidenceScore()).isEqualTo(96);
        assertThat(saved.getReady()).isTrue();
        assertThat(saved.getTotalControls()).isEqualTo(20);
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getUrlHash()).isNotBlank();
    }

    @Test
    void disabledPersistsNothingAndSaysWhy() {
        ValidationHistoryService disabled =
                new ValidationHistoryService(repo, new SelectorDriftDetector(10, 2), false);

        var drift = disabled.record(report(96, true), userId);

        verify(repo, never()).save(any());
        assertThat(drift.severity()).isEqualTo(SelectorDriftDetector.Severity.NO_BASELINE);
        assertThat(drift.reasons()).anySatisfy(r -> assertThat(r).contains("disabled"));
    }

    @Test
    void aPersistenceFailureNeverPropagatesToTheValidationRun() {
        // The report is the deliverable; the row is bookkeeping. Losing the row must not lose the run.
        when(repo.save(any())).thenThrow(new IllegalStateException("db down"));
        var drift = service.record(report(96, true), userId);
        assertThat(drift.severity()).isEqualTo(SelectorDriftDetector.Severity.NO_BASELINE);
    }

    /**
     * The baseline is read <em>before</em> the insert. Otherwise a run would be compared against
     * itself and drift could never fire.
     */
    @Test
    void theCurrentRunIsNeverItsOwnBaseline() {
        when(repo.findTop20ByUrlHashOrderByCreatedAtDesc(anyString()))
                .thenReturn(List.of(stored(98, true), stored(98, true)));

        var drift = service.record(report(80, true), userId);

        assertThat(drift.baselineConfidence()).isEqualTo(98);
        assertThat(drift.isAlerting()).isTrue();
    }

    @Test
    void theSameUrlAlwaysHashesToTheSameSeriesKey() {
        assertThat(ValidationHistoryService.hash(URL)).isEqualTo(ValidationHistoryService.hash("  " + URL + " "));
        assertThat(ValidationHistoryService.hash(URL)).isNotEqualTo(ValidationHistoryService.hash(URL + "2"));
        assertThat(ValidationHistoryService.hash(null)).isNotBlank();
    }

    // ── the campaign dashboard ──

    @Test
    void anUntestedAtsIsAbsentRatherThanShownAsZero() {
        Map<String, Object> campaign = service.campaignReport();
        @SuppressWarnings("unchecked")
        Map<String, Object> platforms = (Map<String, Object>) campaign.get("platforms");
        assertThat(platforms).isEmpty();
        assertThat(String.valueOf(campaign.get("note"))).contains("No verified validation data");
    }

    @Test
    void disabledHistoryReportsNoVerifiedDataRatherThanAnEmptyCampaign() {
        ValidationHistoryService disabled =
                new ValidationHistoryService(repo, new SelectorDriftDetector(10, 2), false);
        Map<String, Object> campaign = disabled.campaignReport();
        assertThat(campaign).containsEntry("enabled", false);
        assertThat(String.valueOf(campaign.get("note"))).contains("no verified data");
    }

    @Test
    void theDashboardReportsPagesTestedConfidenceAndReadiness() {
        when(repo.findTop50ByAtsPlatformOrderByCreatedAtDesc("GREENHOUSE"))
                .thenReturn(List.of(stored(98, true), stored(96, true), stored(97, true)));

        @SuppressWarnings("unchecked")
        Map<String, Object> platforms = (Map<String, Object>) service.campaignReport().get("platforms");
        @SuppressWarnings("unchecked")
        Map<String, Object> greenhouse = (Map<String, Object>) platforms.get("GREENHOUSE");

        assertThat(greenhouse).containsEntry("pagesTested", 3)
                .containsEntry("averageConfidence", 97)
                .containsEntry("ready", true);
        // Same posting three times is one page of real coverage, not three.
        assertThat(greenhouse).containsEntry("distinctPostings", 1L);
    }

    @Test
    void aTrendIsOnlyStatedOnceThereIsEnoughData() {
        when(repo.findTop50ByAtsPlatformOrderByCreatedAtDesc("GREENHOUSE"))
                .thenReturn(List.of(stored(98, true), stored(97, true)));

        @SuppressWarnings("unchecked")
        Map<String, Object> platforms = (Map<String, Object>) service.campaignReport().get("platforms");
        @SuppressWarnings("unchecked")
        Map<String, Object> greenhouse = (Map<String, Object>) platforms.get("GREENHOUSE");
        @SuppressWarnings("unchecked")
        Map<String, Object> trend = (Map<String, Object>) greenhouse.get("trend");

        assertThat(trend).containsEntry("direction", "INSUFFICIENT_DATA");
    }

    @Test
    void aDegradingSeriesIsReportedAsDegrading() {
        // Newest first: recent half averages 70, older half 98.
        List<AtsValidationRun> series = new ArrayList<>(List.of(
                stored(70, true), stored(72, true), stored(98, true), stored(98, true)));
        when(repo.findTop50ByAtsPlatformOrderByCreatedAtDesc("GREENHOUSE")).thenReturn(series);

        @SuppressWarnings("unchecked")
        Map<String, Object> platforms = (Map<String, Object>) service.campaignReport().get("platforms");
        @SuppressWarnings("unchecked")
        Map<String, Object> greenhouse = (Map<String, Object>) platforms.get("GREENHOUSE");
        @SuppressWarnings("unchecked")
        Map<String, Object> trend = (Map<String, Object>) greenhouse.get("trend");

        assertThat(trend).containsEntry("direction", "DEGRADING");
        assertThat((int) trend.get("delta")).isNegative();
    }

    @Test
    void aPlatformWithOnlyFailedRunsReportsNoVerifiedConfidence() {
        AtsValidationRun failed = stored(0, false);
        failed.setStatus(ValidationReport.Status.FAILED.name());
        when(repo.findTop50ByAtsPlatformOrderByCreatedAtDesc("GREENHOUSE")).thenReturn(List.of(failed));

        @SuppressWarnings("unchecked")
        Map<String, Object> platforms = (Map<String, Object>) service.campaignReport().get("platforms");
        @SuppressWarnings("unchecked")
        Map<String, Object> greenhouse = (Map<String, Object>) platforms.get("GREENHOUSE");

        // No fabricated 0% — an absence of completed runs is stated as such.
        assertThat(greenhouse).doesNotContainKey("averageConfidence");
        assertThat(String.valueOf(greenhouse.get("note"))).contains("No completed runs");
    }

    /** The campaign report is served on the unauthenticated diagnostics endpoint. */
    @Test
    void theCampaignReportNeverLeaksUrlsOrUserIds() {
        when(repo.findTop50ByAtsPlatformOrderByCreatedAtDesc("GREENHOUSE"))
                .thenReturn(List.of(stored(98, true), stored(97, true)));

        String rendered = String.valueOf(service.campaignReport());
        assertThat(rendered).doesNotContain(URL).doesNotContain(userId.toString());
    }

    @Test
    void aFailingRepositoryDegradesThatPlatformOnly() {
        when(repo.findTop50ByAtsPlatformOrderByCreatedAtDesc("GREENHOUSE"))
                .thenThrow(new IllegalStateException("db down"));
        when(repo.findTop50ByAtsPlatformOrderByCreatedAtDesc("LEVER"))
                .thenReturn(List.of(stored(90, true)));

        @SuppressWarnings("unchecked")
        Map<String, Object> platforms = (Map<String, Object>) service.campaignReport().get("platforms");
        assertThat(platforms).containsKey("GREENHOUSE").containsKey("LEVER");
        @SuppressWarnings("unchecked")
        Map<String, Object> broken = (Map<String, Object>) platforms.get("GREENHOUSE");
        assertThat(broken).containsEntry("unavailable", true);
    }

    @Test
    void historyForAPostingIsEmptyWhenDisabled() {
        ValidationHistoryService disabled =
                new ValidationHistoryService(repo, new SelectorDriftDetector(10, 2), false);
        assertThat(disabled.historyFor(URL)).isEmpty();
        verify(repo, never()).findTop20ByUrlHashOrderByCreatedAtDesc(anyString());
    }
}
