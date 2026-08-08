package ai.careerpilot.repo;

import ai.careerpilot.domain.ApplicationExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.springframework.transaction.annotation.Propagation;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P7 Action 1 — real-Postgres proof for {@link ApplicationExecutionRepository#claimForSubmit}.
 *
 * <p><b>Why this exists.</b> The prior audit found this atomic conditional UPDATE — the sole
 * mechanism preventing a duplicate real employer submission — was proven only by mocks
 * ({@code when(executions.claimForSubmit(any())).thenReturn(1)} in
 * {@code ApplicationExecutionServiceTest}). No test in this codebase exercised it against a real
 * database, and {@code ApplicationExecutionService.finalizeGuestApplySubmit} calls it with NO
 * surrounding {@code @Transactional} — deliberately, per that method's own comment, on the theory
 * that a bare {@code @Modifying} repository method gets its own transaction from the Spring Data
 * proxy. That theory was checked by decompiling {@code
 * TransactionalRepositoryProxyPostProcessor$RepositoryAnnotationTransactionAttributeSource}
 * (spring-data-commons 4.0.6, the version this project's Boot 4.0.7 parent resolves): {@code
 * computeTransactionAttribute} only ever returns a real {@code @Transactional} annotation found on
 * the method/class chain, or {@code null} — there is no synthesized default. If the method has no
 * {@code @Transactional} anywhere in its chain (this one does not), the repository proxy applies
 * NO transaction demarcation of its own. Whether the call still succeeds therefore depends on
 * behavior this test proves rather than assumes.
 *
 * <p><b>Deliberately no Chromium, no browser, no HTTP.</b> This is database safety only, calling
 * {@link ApplicationExecutionRepository#claimForSubmit} directly from raw worker threads with no
 * ambient Spring transaction — the same thread topology production uses ({@code
 * FormApprovalExecutionWorker} dispatches onto a bounded executor, a separate thread from the
 * {@code @TransactionalEventListener} that published the approval).
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// @DataJpaTest wraps each test method in its own transaction and rolls it back — invisible to
// the worker threads below, which open their own connections/transactions to genuinely race
// against the seeded row. Suspending it here is what makes this a real concurrency test rather
// than one where every worker thread simply can't see data the main thread hasn't committed.
@org.springframework.transaction.annotation.Transactional(propagation = Propagation.NOT_SUPPORTED)
class ApplicationExecutionRepositoryClaimForSubmitTest {

    // pgvector/pgvector, not plain postgres: V1__init.sql runs `CREATE EXTENSION vector`, which the
    // stock postgres image does not ship. Flyway runs all 84 real migrations against this container —
    // this is the actual production schema, not a hand-rolled test-only subset.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    ApplicationExecutionRepository executions;

    @Autowired
    DataSource dataSource;

    private UUID userId;
    private UUID jobId;
    private UUID packageId;

    /**
     * Seeds the real FK chain (organizations -> users -> jobs -> application_package) via raw JDBC,
     * bypassing JPA entirely, so this test depends on nothing but the schema itself and stays honest
     * about what the atomic claim protects: a genuine {@code application_execution} row satisfying
     * every real constraint, not a relaxed test fixture.
     */
    @BeforeEach
    void seedParentRows() throws Exception {
        UUID orgId = UUID.randomUUID();
        userId = UUID.randomUUID();
        jobId = UUID.randomUUID();
        packageId = UUID.randomUUID();
        try (Connection c = dataSource.getConnection()) {
            exec(c, "INSERT INTO organizations (id, name, slug) VALUES (?, 'IT Org', ?)",
                    orgId, "it-org-" + orgId);
            exec(c, "INSERT INTO users (id, org_id, email, password_hash) VALUES (?, ?, ?, 'x')",
                    userId, orgId, "it-" + userId + "@example.test");
            exec(c, "INSERT INTO jobs (id, org_id, title, company, description) "
                            + "VALUES (?, ?, 'Test Role', 'Test Co', 'test posting')",
                    jobId, orgId);
            exec(c, "INSERT INTO application_package (id, user_id, job_id, package_version, status) "
                            + "VALUES (?, ?, ?, 1, 'ASSEMBLED')",
                    packageId, userId, jobId);
        }
    }

    private static void exec(Connection c, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        }
    }

    private UUID insertAwaitingApproval() {
        ApplicationExecution saved = executions.saveAndFlush(ApplicationExecution.builder()
                .userId(userId).jobId(jobId).applicationPackageId(packageId)
                .executionStatus(ApplicationExecution.STATUS_AWAITING_APPROVAL)
                .executionType(ApplicationExecution.TYPE_ATS_CONNECTOR)
                .attemptCount(1)
                .build());
        return saved.getId();
    }

    /**
     * Single-caller sanity check, isolated from the concurrency question: does a bare (no
     * {@code @Transactional}) call to {@code claimForSubmit} work AT ALL, called from a plain worker
     * thread the way production actually calls it? If this fails, the concurrency test below is
     * moot — the real defect would be "never submits," not "sometimes submits twice."
     */
    @Test
    void bareCallFromWorkerThreadClaimsSuccessfully() throws Exception {
        UUID executionId = insertAwaitingApproval();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            int claimed = pool.submit(() -> executions.claimForSubmit(executionId)).get(10, TimeUnit.SECONDS);
            assertThat(claimed).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        ApplicationExecution row = executions.findById(executionId).orElseThrow();
        assertThat(row.getExecutionStatus()).isEqualTo(ApplicationExecution.STATUS_SUBMITTING);
    }

    /**
     * The actual duplicate-submission proof: 20 threads race to claim the SAME row at the SAME
     * instant (a {@link CyclicBarrier} lines them up so this is a genuine race, not a sequence).
     * Exactly one may see {@code claimed == 1}; every other caller must see {@code 0} — never a
     * second {@code 1}, and never an exception that could be mistaken for a safe refusal.
     */
    @Test
    void exactlyOneOfTwentyConcurrentCallersClaimsTheSameExecution() throws Exception {
        UUID executionId = insertAwaitingApproval();
        int callers = 20;

        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CyclicBarrier barrier = new CyclicBarrier(callers);
        AtomicInteger unexpectedErrors = new AtomicInteger();
        List<Future<Integer>> futures = new java.util.ArrayList<>();

        try {
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(15, TimeUnit.SECONDS);
                    return executions.claimForSubmit(executionId);
                }));
            }

            int winners = 0;
            int refusals = 0;
            for (Future<Integer> f : futures) {
                try {
                    int result = f.get(15, TimeUnit.SECONDS);
                    if (result == 1) winners++;
                    else if (result == 0) refusals++;
                    else fail("claimForSubmit returned an impossible row count: " + result);
                } catch (ExecutionException e) {
                    unexpectedErrors.incrementAndGet();
                    System.err.println("claimForSubmit threw on a concurrent caller: " + e.getCause());
                }
            }

            assertThat(unexpectedErrors.get())
                    .as("no concurrent caller should throw — a thrown exception is neither a proven "
                            + "win nor a proven safe refusal")
                    .isZero();
            assertThat(winners).as("exactly one caller may win the claim").isEqualTo(1);
            assertThat(refusals).as("every other caller must be refused, not merely absent")
                    .isEqualTo(callers - 1);
        } finally {
            pool.shutdownNow();
        }

        ApplicationExecution row = executions.findById(executionId).orElseThrow();
        assertThat(row.getExecutionStatus()).isEqualTo(ApplicationExecution.STATUS_SUBMITTING);
    }

    private static void fail(String message) {
        throw new AssertionError(message);
    }
}
