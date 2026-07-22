package ai.careerpilot.companyintel;

import ai.careerpilot.domain.Interview;
import ai.careerpilot.domain.InterviewFeedback;
import ai.careerpilot.domain.Job;
import ai.careerpilot.repo.InterviewFeedbackRepository;
import ai.careerpilot.repo.InterviewRepository;
import ai.careerpilot.repo.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 7.17.2 — verifies the aggregation is real, never fabricates a metric it has no data for,
 * and correctly scopes interviews to the requested company by job→company join.
 */
class CompanyInterviewIntelligenceServiceTest {

    private final UUID userId = UUID.randomUUID();
    private InterviewRepository interviews;
    private InterviewFeedbackRepository feedbackRepo;
    private JobRepository jobs;

    @BeforeEach
    void setUp() {
        interviews = mock(InterviewRepository.class);
        feedbackRepo = mock(InterviewFeedbackRepository.class);
        jobs = mock(JobRepository.class);
    }

    private CompanyInterviewIntelligenceService service(boolean enabled) {
        return new CompanyInterviewIntelligenceService(interviews, feedbackRepo, jobs, enabled);
    }

    @Test
    void disabledIsANoOp() {
        assertThat(service(false).forCompany(userId, "Acme")).isEmpty();
    }

    @Test
    void noInterviewsReturnsZeroRoundsNeverFabricated() {
        when(interviews.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        when(jobs.findAllById(any())).thenReturn(List.of());

        Map<String, Object> result = service(true).forCompany(userId, "Acme").orElseThrow();

        assertThat(result.get("interviewRounds")).isEqualTo(0);
        assertThat(result.get("confidence")).isEqualTo("NONE");
    }

    @Test
    void onlyScopesInterviewsToTheMatchingCompanyByJob() {
        UUID acmeJobId = UUID.randomUUID();
        UUID otherJobId = UUID.randomUUID();
        Interview acmeInterview = Interview.builder().id(UUID.randomUUID()).userId(userId).jobId(acmeJobId)
                .interviewType(Interview.TYPE_TECHNICAL).result(Interview.RESULT_SCHEDULED).build();
        Interview otherInterview = Interview.builder().id(UUID.randomUUID()).userId(userId).jobId(otherJobId)
                .interviewType(Interview.TYPE_TECHNICAL).result(Interview.RESULT_SCHEDULED).build();
        when(interviews.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(acmeInterview, otherInterview));
        when(jobs.findAllById(any())).thenReturn(List.of(
                Job.builder().id(acmeJobId).company("Acme Corp").build(),
                Job.builder().id(otherJobId).company("Other Inc").build()));
        when(feedbackRepo.findByInterviewIdIn(any())).thenReturn(List.of());

        Map<String, Object> result = service(true).forCompany(userId, "Acme Corp").orElseThrow();

        assertThat(result.get("interviewRounds")).isEqualTo(1);
    }

    @Test
    void passRateIsNullWhenNoInterviewHasARecordedOutcome() {
        UUID jobId = UUID.randomUUID();
        Interview interview = Interview.builder().id(UUID.randomUUID()).userId(userId).jobId(jobId)
                .interviewType(Interview.TYPE_TECHNICAL).result(Interview.RESULT_SCHEDULED).build();
        when(interviews.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(interview));
        when(jobs.findAllById(any())).thenReturn(List.of(Job.builder().id(jobId).company("Acme").build()));
        when(feedbackRepo.findByInterviewIdIn(any())).thenReturn(List.of());

        Map<String, Object> result = service(true).forCompany(userId, "Acme").orElseThrow();

        assertThat(result.get("passRate")).isNull();
    }

    @Test
    void passRateComputesFromRealPassedFailedCounts() {
        UUID jobId = UUID.randomUUID();
        Interview passed = Interview.builder().id(UUID.randomUUID()).userId(userId).jobId(jobId)
                .interviewType(Interview.TYPE_TECHNICAL).result(Interview.RESULT_PASSED).build();
        Interview failed = Interview.builder().id(UUID.randomUUID()).userId(userId).jobId(jobId)
                .interviewType(Interview.TYPE_SYSTEM_DESIGN).result(Interview.RESULT_FAILED).build();
        when(interviews.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(passed, failed));
        when(jobs.findAllById(any())).thenReturn(List.of(Job.builder().id(jobId).company("Acme").build()));
        when(feedbackRepo.findByInterviewIdIn(any())).thenReturn(List.of());

        Map<String, Object> result = service(true).forCompany(userId, "Acme").orElseThrow();

        assertThat(result.get("passRate")).isEqualTo(50L);
    }

    @Test
    void frequentlyMentionedTechnologiesComesFromRealFeedbackTextOnly() {
        UUID jobId = UUID.randomUUID();
        UUID interviewId = UUID.randomUUID();
        Interview interview = Interview.builder().id(interviewId).userId(userId).jobId(jobId)
                .interviewType(Interview.TYPE_TECHNICAL).result(Interview.RESULT_SCHEDULED).build();
        when(interviews.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(interview));
        when(jobs.findAllById(any())).thenReturn(List.of(Job.builder().id(jobId).company("Acme").build()));
        when(feedbackRepo.findByInterviewIdIn(any())).thenReturn(List.of(
                InterviewFeedback.builder().interviewId(interviewId)
                        .feedback("Asked a lot about java and kubernetes").rating(4).build()));

        Map<String, Object> result = service(true).forCompany(userId, "Acme").orElseThrow();

        @SuppressWarnings("unchecked")
        List<String> topTech = (List<String>) result.get("frequentlyMentionedTechnologies");
        assertThat(topTech).contains("java", "kubernetes");
    }

    @Test
    void neverThrowsWhenCompanyNameIsBlank() {
        assertThat(service(true).forCompany(userId, "")).isEmpty();
        assertThat(service(true).forCompany(userId, null)).isEmpty();
    }
}
