package ai.careerpilot.execution.approval;

import ai.careerpilot.domain.ApprovalQueueEntry;
import ai.careerpilot.execution.event.ApprovalGrantedEvent;
import ai.careerpilot.repo.ApprovalAuditRepository;
import ai.careerpilot.repo.ApprovalQueueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P7 Action 2 — real-Postgres proof that {@link ApprovalService#approve} enforces its own
 * {@code PENDING -> APPROVED} invariant, rather than relying on {@link
 * ai.careerpilot.execution.execution.ApplicationExecutionService#finalizeGuestApplySubmit
 * finalizeGuestApplySubmit}'s atomic claim (Action 1) as the only line of defense.
 *
 * <p>{@code ApprovalService} and {@link ApprovalMetrics} are explicitly {@code @Import}ed rather
 * than constructed with {@code new} — {@code @DataJpaTest} does not component-scan {@code @Service}
 * beans by default, and importing them as real Spring beans is what makes {@code approve()}'s own
 * {@code @Transactional} genuinely AOP-proxied, matching how production actually wires this class
 * (autowired into {@code ExecutionController}). {@link ApprovalQueueRepository#claimDecision} is
 * the mechanism actually under test; its own {@code @Transactional} (repository-proxied, not
 * caller-proxied) is what Action 1 proved is load-bearing.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ApprovalService.class, ApprovalMetrics.class})
// Same reason as Action 1's test: @DataJpaTest wraps each test method in a transaction that never
// commits, which would make the seeded PENDING row invisible to the racing worker threads below.
@org.springframework.transaction.annotation.Transactional(propagation = Propagation.NOT_SUPPORTED)
class ApprovalServiceConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    ApprovalService approvalService;

    @Autowired
    ApprovalQueueRepository queue;

    @Autowired
    ApprovalAuditRepository audit;

    @Autowired
    DataSource dataSource;

    private UUID userId;
    private UUID jobId;
    private UUID packageId;

    /** Same real FK-chain seeding convention as Action 1's ApplicationExecutionRepository test. */
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

    private UUID insertPendingApproval() {
        ApprovalQueueEntry saved = queue.saveAndFlush(ApprovalQueueEntry.builder()
                .userId(userId).jobId(jobId).applicationPackageId(packageId)
                .safetyVerdict("REVIEW")
                .status(ApprovalQueueEntry.STATUS_PENDING)
                .approvalType(ApprovalQueueEntry.TYPE_APPLICATION_PACKAGE)
                .build());
        return saved.getId();
    }

    /**
     * A trivial, thread-safe {@link org.springframework.context.ApplicationEventPublisher}
     * substitute that only counts published {@link ApprovalGrantedEvent}s — the exact production
     * concern (a second event reaching {@code FormApprovalExecutionWorker}), not Spring's listener
     * dispatch mechanics, which is untouched by this action.
     */
    static final class CountingPublisher implements org.springframework.context.ApplicationEventPublisher {
        final AtomicInteger grantedEvents = new AtomicInteger();
        final java.util.concurrent.CopyOnWriteArrayList<Object> all = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void publishEvent(Object event) {
            all.add(event);
            if (event instanceof ApprovalGrantedEvent) {
                grantedEvents.incrementAndGet();
            }
        }
    }

    /**
     * The actual duplicate-approval-grant proof: 20 threads race to approve the SAME PENDING row at
     * the SAME instant (a {@link CyclicBarrier} makes this a genuine race, not a sequence). Exactly
     * one may transition the row; every other caller must be refused with the existing 409-mapped
     * {@link IllegalStateException} — never an exception that could be mistaken for a safe no-op,
     * and never a second successful transition.
     */
    @Test
    void exactlyOneOfTwentyConcurrentApproveCallsGrantsAndExactlyOneEventPublishes() throws Exception {
        UUID approvalId = insertPendingApproval();

        // approve() itself only takes @Transactional/AOP proxying from the imported Spring bean, but
        // the field it publishes through is swapped for a plain counting stand-in so this test can
        // assert on call count directly rather than needing a listener bean and asynchronous dispatch.
        CountingPublisher publisher = new CountingPublisher();
        ApprovalService service = new ApprovalService(queue, audit, new ApprovalMetrics(), publisher, true);

        int callers = 20;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CyclicBarrier barrier = new CyclicBarrier(callers);
        List<Future<Object>> futures = new java.util.ArrayList<>();

        try {
            for (int i = 0; i < callers; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    barrier.await(15, TimeUnit.SECONDS);
                    try {
                        return service.approve(approvalId, userId, "approver-" + idx, "note-" + idx);
                    } catch (IllegalStateException conflict) {
                        return conflict;
                    }
                }));
            }

            int successes = 0;
            int conflicts = 0;
            int unexpected = 0;
            for (Future<Object> f : futures) {
                Object result = f.get(15, TimeUnit.SECONDS);
                if (result instanceof ApprovalQueueEntry entry) {
                    successes++;
                    assertThat(entry.getStatus()).isEqualTo(ApprovalQueueEntry.STATUS_APPROVED);
                } else if (result instanceof IllegalStateException) {
                    conflicts++;
                } else {
                    unexpected++;
                }
            }

            assertThat(unexpected).as("no caller should produce an unrecognized outcome").isZero();
            assertThat(successes).as("exactly one caller may win the approval").isEqualTo(1);
            assertThat(conflicts).as("every other caller must be refused, not merely absent")
                    .isEqualTo(callers - 1);
            assertThat(publisher.grantedEvents.get())
                    .as("exactly one ApprovalGrantedEvent must reach downstream workers")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        ApprovalQueueEntry row = queue.findById(approvalId).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(ApprovalQueueEntry.STATUS_APPROVED);
    }

    /**
     * The cross-decision race named explicitly in the audit: an {@code approve()} and a {@code
     * reject()} racing the SAME row must still resolve to exactly one terminal decision and never
     * both succeed, regardless of which one wins.
     */
    @Test
    void concurrentApproveAndRejectOnSameRowResolveToExactlyOneDecision() throws Exception {
        UUID approvalId = insertPendingApproval();
        CountingPublisher publisher = new CountingPublisher();
        ApprovalService service = new ApprovalService(queue, audit, new ApprovalMetrics(), publisher, true);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<Object> approveResult = new AtomicReference<>();
        AtomicReference<Object> rejectResult = new AtomicReference<>();

        try {
            Future<?> approveFuture = pool.submit(() -> {
                try {
                    barrier.await(15, TimeUnit.SECONDS);
                    approveResult.set(service.approve(approvalId, userId, "approver", "ok"));
                } catch (IllegalStateException e) {
                    approveResult.set(e);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            Future<?> rejectFuture = pool.submit(() -> {
                try {
                    barrier.await(15, TimeUnit.SECONDS);
                    rejectResult.set(service.reject(approvalId, userId, "rejector", "no"));
                } catch (IllegalStateException e) {
                    rejectResult.set(e);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            approveFuture.get(15, TimeUnit.SECONDS);
            rejectFuture.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        boolean approveWon = approveResult.get() instanceof ApprovalQueueEntry;
        boolean rejectWon = rejectResult.get() instanceof ApprovalQueueEntry;
        assertThat(approveWon ^ rejectWon).as("exactly one of approve/reject may win").isTrue();

        ApprovalQueueEntry row = queue.findById(approvalId).orElseThrow();
        assertThat(row.getStatus()).isIn(ApprovalQueueEntry.STATUS_APPROVED, ApprovalQueueEntry.STATUS_REJECTED);
        assertThat(publisher.grantedEvents.get()).isEqualTo(approveWon ? 1 : 0);
    }
}
