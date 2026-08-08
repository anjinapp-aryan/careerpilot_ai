package ai.careerpilot.retention;

import ai.careerpilot.storage.S3StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P2 Work Item 3 — screenshot retention.
 *
 * <p>The load-bearing tests are the ones asserting what is <b>not</b> deleted. Validation
 * screenshots are diagnostics; {@code execution-screenshots/} holds the image a human reviewed at
 * the mandatory approval gate and the evidence behind a submission's verification verdict. Deleting
 * one of those would destroy the record of what a candidate approved sending to an employer.
 */
class ScreenshotRetentionServiceTest {

    private S3StorageService storage;

    @BeforeEach
    void setUp() {
        storage = mock(S3StorageService.class);
        when(storage.listKeysOlderThan(anyString(), any(), anyInt())).thenReturn(List.of());
    }

    private ScreenshotRetentionService service(boolean enabled, int days) {
        return new ScreenshotRetentionService(storage, enabled, days, 200);
    }

    // ── what must never be deleted ──

    @ParameterizedTest(name = "protected: {0}")
    @ValueSource(strings = {
            "execution-screenshots/abc/form.png",
            "execution-screenshots/abc/confirmation.png",
            "resumes/user/cv.pdf",
            "resume-versions/user/v3.docx",
            "application-packages/x.pdf"})
    void nonDiagnosticObjectsAreNeverDeletable(String key) {
        assertThat(ScreenshotRetentionService.isDeletable(key)).isFalse();
    }

    @Test
    @DisplayName("human-approval evidence is excluded structurally, not by configuration")
    void executionScreenshotsAreNeverSwept() {
        // There is no flag, window or value that makes this prefix eligible: the sweep only ever
        // lists the validation prefix, and isDeletable refuses the execution prefix outright.
        service(true, 30).sweep();

        ArgumentCaptor<String> prefix = ArgumentCaptor.forClass(String.class);
        verify(storage).listKeysOlderThan(prefix.capture(), any(), anyInt());
        assertThat(prefix.getValue()).isEqualTo(ScreenshotRetentionService.VALIDATION_PREFIX);
        assertThat(prefix.getValue()).isNotEqualTo(ScreenshotRetentionService.PROTECTED_EXECUTION_PREFIX);
    }

    @Test
    @DisplayName("a protected key returned by storage is still refused at delete time")
    void aProtectedKeyIsRefusedEvenIfListingReturnsIt() {
        // Defence in depth: if the listing were ever mis-scoped, the loop must not act on it.
        when(storage.listKeysOlderThan(anyString(), any(), anyInt())).thenReturn(List.of(
                "browser-validation/greenhouse/a.png",
                "execution-screenshots/abc/form.png"));

        int removed = service(true, 30).sweep();

        assertThat(removed).isEqualTo(1);
        verify(storage).delete("browser-validation/greenhouse/a.png");
        verify(storage, never()).delete("execution-screenshots/abc/form.png");
    }

    @Test
    void validationScreenshotsAreDeletable() {
        assertThat(ScreenshotRetentionService.isDeletable("browser-validation/lever/x.png")).isTrue();
    }

    @Test
    void nullAndBlankKeysAreNeverDeletable() {
        assertThat(ScreenshotRetentionService.isDeletable(null)).isFalse();
        assertThat(ScreenshotRetentionService.isDeletable("")).isFalse();
        assertThat(ScreenshotRetentionService.isDeletable("   ")).isFalse();
    }

    // ── the sweep itself ──

    @Test
    void deletesEveryDiagnosticObjectReturned() {
        when(storage.listKeysOlderThan(anyString(), any(), anyInt())).thenReturn(List.of(
                "browser-validation/greenhouse/a.png",
                "browser-validation/lever/b.png",
                "browser-validation/workday/c.png"));

        assertThat(service(true, 30).sweep()).isEqualTo(3);
        verify(storage, times(3)).delete(anyString());
    }

    @Test
    @DisplayName("the cutoff is the configured window, not an arbitrary instant")
    void theCutoffReflectsTheConfiguredRetention() {
        Instant before = Instant.now().minus(java.time.Duration.ofDays(30));

        service(true, 30).sweep();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(storage).listKeysOlderThan(anyString(), cutoff.capture(), anyInt());
        assertThat(cutoff.getValue()).isCloseTo(before, org.assertj.core.api.Assertions.within(
                5, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("a zero or negative window never means 'delete everything now'")
    void aMisconfiguredWindowIsClampedToAtLeastOneDay() {
        new ScreenshotRetentionService(storage, true, 0, 200).sweep();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(storage).listKeysOlderThan(anyString(), cutoff.capture(), anyInt());
        assertThat(cutoff.getValue()).isBefore(Instant.now().minus(java.time.Duration.ofHours(23)));
    }

    @Test
    void theSweepIsBoundedSoItCannotMonopoliseASingleVcpu() {
        new ScreenshotRetentionService(storage, true, 30, 50).sweep();

        verify(storage).listKeysOlderThan(anyString(), any(), eq(50));
    }

    // ── retry safety ──

    @Test
    @DisplayName("one failing object does not strand the rest, and is retried next sweep")
    void perObjectFailuresAreIsolated() {
        when(storage.listKeysOlderThan(anyString(), any(), anyInt())).thenReturn(List.of(
                "browser-validation/a.png", "browser-validation/b.png", "browser-validation/c.png"));
        doThrow(new RuntimeException("storage blip")).when(storage).delete("browser-validation/b.png");

        ScreenshotRetentionService svc = service(true, 30);
        assertThat(svc.sweep()).isEqualTo(2);

        // Deletion is idempotent, so nothing special is needed to make the retry safe.
        assertThat(svc.snapshot()).containsEntry("failures", 1L);
    }

    @Test
    void aFailedListingDeletesNothingRatherThanGuessing() {
        when(storage.listKeysOlderThan(anyString(), any(), anyInt()))
                .thenThrow(new RuntimeException("storage down"));

        assertThat(service(true, 30).sweep()).isZero();
        verify(storage, never()).delete(anyString());
    }

    @Test
    void aThrowingSweepNeverEscapesTheScheduler() {
        when(storage.listKeysOlderThan(anyString(), any(), anyInt()))
                .thenThrow(new RuntimeException("boom"));

        service(true, 30).scheduledSweep();   // must not propagate
    }

    // ── gating ──

    @Test
    void disabledTouchesStorageAtAll() {
        ScreenshotRetentionService svc = service(false, 30);

        assertThat(svc.sweep()).isZero();
        svc.scheduledSweep();

        verifyNoInteractions(storage);
    }

    @Test
    void theSnapshotStatesThePolicyIncludingWhatIsProtected() {
        var snap = service(true, 30).snapshot();

        assertThat(snap).containsEntry("enabled", true)
                .containsEntry("validationRetentionDays", 30L)
                .containsEntry("protectedPrefix", "execution-screenshots/");
        assertThat(String.valueOf(snap.get("policy"))).contains("never deleted");
    }
}
