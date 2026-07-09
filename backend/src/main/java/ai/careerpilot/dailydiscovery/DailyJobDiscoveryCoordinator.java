package ai.careerpilot.dailydiscovery;

import ai.careerpilot.domain.DailyDiscoveryRun;
import ai.careerpilot.domain.User;
import ai.careerpilot.jobdiscovery.JobAggregationService;
import ai.careerpilot.jobdiscovery.dedup.JobDuplicateDetectionService;
import ai.careerpilot.repo.DailyDiscoveryRunRepository;
import ai.careerpilot.repo.UserRepository;
import ai.careerpilot.workflow.correlation.WorkflowCorrelationService;
import ai.careerpilot.workflow.correlation.WorkflowDeadLetterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Phase 5A — orchestrates one end-to-end daily discovery run: fetch (reuses
 * {@link JobAggregationService}, which already fans out across every configured
 * {@code JobProvider} including this phase's Greenhouse/Lever additions) → dedupe (reuses
 * {@link JobDuplicateDetectionService}) → per-user match/classify/summarize (delegates to
 * {@link DailyJobDiscoveryService} and {@link DailyCareerSummaryGenerator}). Every stage is
 * wrapped so one user's or one stage's failure never aborts the run for the rest — failures are
 * logged to the existing {@link WorkflowDeadLetterService} sink and the run finishes PARTIAL
 * rather than FAILED whenever at least one stage/user succeeded.
 */
@Service
public class DailyJobDiscoveryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(DailyJobDiscoveryCoordinator.class);
    private static final String WORKFLOW_TYPE = "DAILY_JOB_DISCOVERY";

    private final JobAggregationService aggregation;
    private final JobDuplicateDetectionService dedup;
    private final UserRepository users;
    private final DailyJobDiscoveryService discoveryService;
    private final DailyCareerSummaryGenerator summaryGenerator;
    private final DailyDiscoveryRunRepository runs;
    private final WorkflowCorrelationService correlation;
    private final WorkflowDeadLetterService deadLetters;
    private final DailyJobDiscoveryMetrics metrics;

    public DailyJobDiscoveryCoordinator(JobAggregationService aggregation,
                                        JobDuplicateDetectionService dedup,
                                        UserRepository users,
                                        DailyJobDiscoveryService discoveryService,
                                        DailyCareerSummaryGenerator summaryGenerator,
                                        DailyDiscoveryRunRepository runs,
                                        WorkflowCorrelationService correlation,
                                        WorkflowDeadLetterService deadLetters,
                                        DailyJobDiscoveryMetrics metrics) {
        this.aggregation = aggregation;
        this.dedup = dedup;
        this.users = users;
        this.discoveryService = discoveryService;
        this.summaryGenerator = summaryGenerator;
        this.runs = runs;
        this.correlation = correlation;
        this.deadLetters = deadLetters;
        this.metrics = metrics;
    }

    public UUID runOnce() {
        long startMs = System.currentTimeMillis();
        metrics.recordRunStart();
        UUID correlationId = correlation.start(WORKFLOW_TYPE, null, null, null);
        DailyDiscoveryRun run = runs.save(DailyDiscoveryRun.builder()
                .correlationId(correlationId).status(DailyDiscoveryRun.STATUS_RUNNING).build());
        UUID runId = run.getId();

        int fetched = 0, persisted = 0, deduped = 0, usersOk = 0, usersFailed = 0;
        boolean fetchFailed = false;

        try {
            var summary = aggregation.discoverAll();
            fetched = summary.totalFetched();
            persisted = summary.totalPersisted();
            correlation.advance(correlationId, "FETCH_NORMALIZE", "IN_PROGRESS");
        } catch (Exception e) {
            fetchFailed = true;
            deadLetters.record(correlationId, WORKFLOW_TYPE, "FETCH_NORMALIZE", null, e);
            log.warn("Daily discovery fetch stage failed run={}: {}", runId, e.toString());
        }

        try {
            deduped = dedup.detectDuplicates();
            correlation.advance(correlationId, "DEDUPLICATE", "IN_PROGRESS");
        } catch (Exception e) {
            deadLetters.record(correlationId, WORKFLOW_TYPE, "DEDUPLICATE", null, e);
            log.warn("Daily discovery dedup stage failed run={}: {}", runId, e.toString());
        }

        List<DailyJobDiscoveryService.UserDiscoverySnapshot> snapshots = new ArrayList<>();
        for (User user : users.findAll()) {
            if (!"ACTIVE".equals(user.getStatus())) continue;
            try {
                var snapshot = discoveryService.processUser(runId, user.getId());
                snapshots.add(snapshot);
                summaryGenerator.generate(runId, user, snapshot);
                usersOk++;
            } catch (Exception e) {
                usersFailed++;
                deadLetters.record(correlationId, WORKFLOW_TYPE, "PROFILE_MATCH_AND_SUMMARY",
                        "userId=" + user.getId(), e);
                log.warn("Daily discovery per-user pass failed user={} run={}: {}", user.getId(), runId, e.toString());
            }
        }

        try {
            discoveryService.persistAggregate(runId, snapshots);
        } catch (Exception e) {
            deadLetters.record(correlationId, WORKFLOW_TYPE, "ANALYTICS_AGGREGATE", null, e);
        }

        String status = fetchFailed && usersOk == 0 ? DailyDiscoveryRun.STATUS_FAILED
                : (fetchFailed || usersFailed > 0) ? DailyDiscoveryRun.STATUS_PARTIAL
                : DailyDiscoveryRun.STATUS_SUCCESS;

        run.setStatus(status);
        run.setJobsFetched(fetched);
        run.setJobsNormalized(persisted);
        run.setJobsDeduped(deduped);
        run.setUsersProcessed(usersOk);
        run.setFinishedAt(Instant.now());
        runs.save(run);
        correlation.advance(correlationId, "COMPLETE", status);

        long durationMs = System.currentTimeMillis() - startMs;
        metrics.recordRunFinished(status, durationMs, usersOk);
        log.info("Daily discovery run {} complete: status={} fetched={} deduped={} usersOk={} usersFailed={} durationMs={}",
                runId, status, fetched, deduped, usersOk, usersFailed, durationMs);
        return runId;
    }
}
