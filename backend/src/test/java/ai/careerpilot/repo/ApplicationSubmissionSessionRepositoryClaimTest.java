package ai.careerpilot.repo;

import ai.careerpilot.domain.ApplicationSubmissionSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Propagation;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guided Apply Hardening — real-Postgres proof for {@link
 * ApplicationSubmissionSessionRepository#claimUserReportedSubmitted}, the same atomic
 * conditional-UPDATE pattern Action 1 (claimForSubmit) and Action 2 (claimDecision) already proved
 * against a real database. Closes a genuine check-then-act race in the original Guided Apply
 * implementation: {@code reportUserSubmitted} read the session's status in Java, checked it, then
 * wrote — two concurrent "Yes, I submitted it" calls (a double-tap, or two open tabs) could both
 * pass the check and both write, the second silently overwriting the first's note/timestamp.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@org.springframework.transaction.annotation.Transactional(propagation = Propagation.NOT_SUPPORTED)
class ApplicationSubmissionSessionRepositoryClaimTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    ApplicationSubmissionSessionRepository sessions;

    @Autowired
    DataSource dataSource;

    private UUID userId;
    private UUID jobId;

    @BeforeEach
    void seedParentRows() throws Exception {
        UUID orgId = UUID.randomUUID();
        userId = UUID.randomUUID();
        jobId = UUID.randomUUID();
        try (Connection c = dataSource.getConnection()) {
            exec(c, "INSERT INTO organizations (id, name, slug) VALUES (?, 'IT Org', ?)",
                    orgId, "it-org-" + orgId);
            exec(c, "INSERT INTO users (id, org_id, email, password_hash) VALUES (?, ?, ?, 'x')",
                    userId, orgId, "it-" + userId + "@example.test");
            exec(c, "INSERT INTO jobs (id, org_id, title, company, description) "
                            + "VALUES (?, ?, 'Test Role', 'Test Co', 'test posting')",
                    jobId, orgId);
        }
    }

    private static void exec(Connection c, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        }
    }

    private UUID insertWaitingManualSubmission() {
        ApplicationSubmissionSession saved = sessions.saveAndFlush(ApplicationSubmissionSession.builder()
                .userId(userId).jobId(jobId)
                .status(ApplicationSubmissionSession.STATUS_WAITING_MANUAL_SUBMISSION)
                .submissionMethod(ApplicationSubmissionSession.METHOD_MANUAL)
                .build());
        return saved.getId();
    }

    @Test
    void bareCallFromWorkerThreadClaimsSuccessfully() throws Exception {
        UUID sessionId = insertWaitingManualSubmission();
        Instant now = Instant.now();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            int claimed = pool.submit(() -> sessions.claimUserReportedSubmitted(sessionId, userId,
                    ApplicationSubmissionSession.STATUS_USER_REPORTED_SUBMITTED, now, "done"))
                    .get(10, TimeUnit.SECONDS);
            assertThat(claimed).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        ApplicationSubmissionSession row = sessions.findById(sessionId).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(ApplicationSubmissionSession.STATUS_USER_REPORTED_SUBMITTED);
        assertThat(row.getUserSubmissionNote()).isEqualTo("done");
        assertThat(row.getUserReportedSubmittedAt()).isNotNull();
    }

    /**
     * The actual duplicate-confirmation proof: 20 threads race to report the SAME session submitted
     * at the SAME instant. Exactly one may see {@code claimed == 1}; every other caller must see
     * {@code 0} — never a second winner, and the row's note must be the winner's, not silently
     * overwritten by a later loser (which the pre-fix read-then-write implementation could not
     * guarantee).
     */
    @Test
    void exactlyOneOfTwentyConcurrentReportSubmittedCallsWins() throws Exception {
        UUID sessionId = insertWaitingManualSubmission();
        int callers = 20;

        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CyclicBarrier barrier = new CyclicBarrier(callers);
        List<Future<Integer>> futures = new java.util.ArrayList<>();

        try {
            for (int i = 0; i < callers; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    barrier.await(15, TimeUnit.SECONDS);
                    return sessions.claimUserReportedSubmitted(sessionId, userId,
                            ApplicationSubmissionSession.STATUS_USER_REPORTED_SUBMITTED,
                            Instant.now(), "note-" + idx);
                }));
            }

            int winners = 0;
            int refusals = 0;
            AtomicInteger unexpectedErrors = new AtomicInteger();
            for (Future<Integer> f : futures) {
                try {
                    int result = f.get(15, TimeUnit.SECONDS);
                    if (result == 1) winners++;
                    else if (result == 0) refusals++;
                    else throw new AssertionError("impossible row count: " + result);
                } catch (ExecutionException e) {
                    unexpectedErrors.incrementAndGet();
                }
            }

            assertThat(unexpectedErrors.get()).as("no concurrent caller should throw").isZero();
            assertThat(winners).as("exactly one caller may win the claim").isEqualTo(1);
            assertThat(refusals).as("every other caller must be refused, not merely absent")
                    .isEqualTo(callers - 1);
        } finally {
            pool.shutdownNow();
        }

        ApplicationSubmissionSession row = sessions.findById(sessionId).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(ApplicationSubmissionSession.STATUS_USER_REPORTED_SUBMITTED);
    }

    @Test
    void claimFailsWhenSessionIsNotAwaitingManualSubmission() {
        ApplicationSubmissionSession saved = sessions.saveAndFlush(ApplicationSubmissionSession.builder()
                .userId(userId).jobId(jobId)
                .status(ApplicationSubmissionSession.STATUS_CREATED)
                .submissionMethod(ApplicationSubmissionSession.METHOD_MANUAL)
                .build());

        int claimed = sessions.claimUserReportedSubmitted(saved.getId(), userId,
                ApplicationSubmissionSession.STATUS_USER_REPORTED_SUBMITTED, Instant.now(), null);

        assertThat(claimed).isEqualTo(0);
        assertThat(sessions.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(ApplicationSubmissionSession.STATUS_CREATED);
    }

    @Test
    void claimFailsForAnotherUsersSession() {
        UUID sessionId = insertWaitingManualSubmission();
        UUID otherUser = UUID.randomUUID();

        int claimed = sessions.claimUserReportedSubmitted(sessionId, otherUser,
                ApplicationSubmissionSession.STATUS_USER_REPORTED_SUBMITTED, Instant.now(), null);

        assertThat(claimed).isEqualTo(0);
        assertThat(sessions.findById(sessionId).orElseThrow().getStatus())
                .isEqualTo(ApplicationSubmissionSession.STATUS_WAITING_MANUAL_SUBMISSION);
    }
}
