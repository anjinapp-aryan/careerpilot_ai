package ai.careerpilot.submission.validation;

import ai.careerpilot.domain.Job;
import ai.careerpilot.repo.JobRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobValidationServiceTest {

    private final JobRepository jobs = mock(JobRepository.class);
    private final JobValidationService service = new JobValidationService(jobs);

    private static Job.JobBuilder validJobBuilder() {
        return Job.builder().title("Software Engineer").company("Acme")
                .sourceUrl("https://boards.greenhouse.io/acme/jobs/1").description("desc");
    }

    @Test
    void nullJobIdIsInvalid() {
        JobValidationService.ValidationResult result = service.validate(null);
        assertFalse(result.valid());
        assertTrue(result.reasons().contains("jobId is required"));
    }

    @Test
    void missingJobIsInvalid() {
        UUID id = UUID.randomUUID();
        when(jobs.findById(id)).thenReturn(Optional.empty());
        JobValidationService.ValidationResult result = service.validate(id);
        assertFalse(result.valid());
        assertTrue(result.reasons().contains("job not found"));
    }

    @Test
    void blankCompanyIsInvalid() {
        UUID id = UUID.randomUUID();
        Job job = validJobBuilder().id(id).company("  ").build();
        when(jobs.findById(id)).thenReturn(Optional.of(job));
        JobValidationService.ValidationResult result = service.validate(id);
        assertFalse(result.valid());
        assertTrue(result.reasons().contains("job has no resolvable company"));
    }

    @Test
    void nullCompanyIsInvalid() {
        UUID id = UUID.randomUUID();
        Job job = validJobBuilder().id(id).company(null).build();
        when(jobs.findById(id)).thenReturn(Optional.of(job));
        JobValidationService.ValidationResult result = service.validate(id);
        assertFalse(result.valid());
        assertTrue(result.reasons().contains("job has no resolvable company"));
    }

    @Test
    void blankTitleIsInvalid() {
        UUID id = UUID.randomUUID();
        Job job = validJobBuilder().id(id).title("").build();
        when(jobs.findById(id)).thenReturn(Optional.of(job));
        JobValidationService.ValidationResult result = service.validate(id);
        assertFalse(result.valid());
        assertTrue(result.reasons().contains("job has no title"));
    }

    @Test
    void missingApplyUrlIsInvalid() {
        UUID id = UUID.randomUUID();
        Job job = validJobBuilder().id(id).sourceUrl(null).externalUrl(null).build();
        when(jobs.findById(id)).thenReturn(Optional.of(job));
        JobValidationService.ValidationResult result = service.validate(id);
        assertFalse(result.valid());
        assertTrue(result.reasons().contains("job has no reachable apply URL"));
    }

    @Test
    void malformedApplyUrlIsInvalid() {
        UUID id = UUID.randomUUID();
        Job job = validJobBuilder().id(id).sourceUrl("not-a-url").externalUrl(null).build();
        when(jobs.findById(id)).thenReturn(Optional.of(job));
        JobValidationService.ValidationResult result = service.validate(id);
        assertFalse(result.valid());
        assertTrue(result.reasons().contains("job has no reachable apply URL"));
    }

    @Test
    void ftpUrlIsMalformed() {
        UUID id = UUID.randomUUID();
        Job job = validJobBuilder().id(id).sourceUrl("ftp://example.com/job").externalUrl(null).build();
        when(jobs.findById(id)).thenReturn(Optional.of(job));
        JobValidationService.ValidationResult result = service.validate(id);
        assertFalse(result.valid());
    }

    @Test
    void allReasonsAccumulateWhenEverythingIsWrong() {
        UUID id = UUID.randomUUID();
        Job job = Job.builder().id(id).title("").company("").sourceUrl(null).externalUrl(null).description("d").build();
        when(jobs.findById(id)).thenReturn(Optional.of(job));
        JobValidationService.ValidationResult result = service.validate(id);
        assertFalse(result.valid());
        assertEquals(3, result.reasons().size());
    }

    @Test
    void fullyValidJobPasses() {
        UUID id = UUID.randomUUID();
        Job job = validJobBuilder().id(id).build();
        when(jobs.findById(id)).thenReturn(Optional.of(job));
        JobValidationService.ValidationResult result = service.validate(id);
        assertTrue(result.valid());
        assertTrue(result.reasons().isEmpty());
        assertSame(job, result.job());
    }

    @Test
    void httpUrlIsAcceptedNotOnlyHttps() {
        UUID id = UUID.randomUUID();
        Job job = validJobBuilder().id(id).sourceUrl("http://careers.acme.com/apply/1").build();
        when(jobs.findById(id)).thenReturn(Optional.of(job));
        assertTrue(service.validate(id).valid());
    }

    @Test
    void applyUrlHelperPrefersSourceUrlOverExternalUrl() {
        Job job = Job.builder().sourceUrl("https://source.example.com/1")
                .externalUrl("https://external.example.com/1").build();
        assertEquals("https://source.example.com/1", JobValidationService.applyUrl(job));
    }

    @Test
    void applyUrlHelperFallsBackToExternalUrlWhenSourceUrlBlank() {
        Job job = Job.builder().sourceUrl("  ").externalUrl("https://external.example.com/1").build();
        assertEquals("https://external.example.com/1", JobValidationService.applyUrl(job));
    }

    @Test
    void applyUrlHelperIsNullWhenBothBlank() {
        Job job = Job.builder().sourceUrl(null).externalUrl(null).build();
        assertNull(JobValidationService.applyUrl(job));
        Job job2 = Job.builder().sourceUrl("").externalUrl("  ").build();
        assertNull(JobValidationService.applyUrl(job2));
    }

    @Test
    void applyUrlHelperIsNullForNullJob() {
        assertNull(JobValidationService.applyUrl(null));
    }
}
