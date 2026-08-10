package ai.careerpilot.submission.reuse;

import ai.careerpilot.domain.Job;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JobFingerprintTest {

    @Test
    void preferProviderAndExternalIdWhenBothPresent() {
        Job job = Job.builder().id(UUID.randomUUID()).source("greenhouse").externalId("8646556002")
                .sourceUrl("https://job-boards.greenhouse.io/gitlab/jobs/8646556002").build();

        assertThat(JobFingerprint.of(job)).isEqualTo("provider:greenhouse:8646556002");
    }

    @Test
    void fallsBackToNormalizedUrlWhenNoExternalId() {
        Job job = Job.builder().id(UUID.randomUUID())
                .sourceUrl("https://boards.greenhouse.io/acme/jobs/1?utm_source=linkedin").build();

        assertThat(JobFingerprint.of(job)).isEqualTo("url:boards.greenhouse.io/acme/jobs/1");
    }

    @Test
    void survivesTitleSalaryDescriptionChangesBecauseTheyAreNeverConsulted() {
        UUID id = UUID.randomUUID();
        Job before = Job.builder().id(id).source("greenhouse").externalId("1")
                .title("Engineer").description("old").salaryRange("100k").build();
        Job after = Job.builder().id(id).source("greenhouse").externalId("1")
                .title("Senior Engineer II").description("completely different").salaryRange("150k").build();

        assertThat(JobFingerprint.of(before)).isEqualTo(JobFingerprint.of(after));
    }

    @Test
    void fallsBackToJobRowIdWhenNoStableIdentityExistsAtAll() {
        UUID id = UUID.randomUUID();
        Job job = Job.builder().id(id).build();

        assertThat(JobFingerprint.of(job)).isEqualTo("job:" + id);
    }

    @Test
    void queryParametersDoNotMintADifferentFingerprint() {
        Job a = Job.builder().id(UUID.randomUUID()).sourceUrl("https://boards.greenhouse.io/acme/jobs/1?a=1").build();
        Job b = Job.builder().id(UUID.randomUUID()).sourceUrl("https://boards.greenhouse.io/acme/jobs/1?b=2").build();

        assertThat(JobFingerprint.of(a)).isEqualTo(JobFingerprint.of(b));
    }

    @Test
    void differentJobsGetDifferentFingerprints() {
        Job a = Job.builder().id(UUID.randomUUID()).source("greenhouse").externalId("1").build();
        Job b = Job.builder().id(UUID.randomUUID()).source("greenhouse").externalId("2").build();

        assertThat(JobFingerprint.of(a)).isNotEqualTo(JobFingerprint.of(b));
    }
}
