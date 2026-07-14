package ai.careerpilot.jobdiscovery.discovery;

import ai.careerpilot.jobdiscovery.enterprise.CompanyConnector;
import ai.careerpilot.repo.CompanyConnectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Gap A — Company Discovery Agent orchestrator. For each configured candidate slug (never a
 * hardcoded company list — read from {@code career.discovery.company-agent.candidates}), runs
 * every configured {@link CompanySource#probe} and, on a hit, inserts a new {@link
 * CompanyConnector} row with {@code enabled=false}/{@code discoveryStatus=PENDING_APPROVAL} —
 * mirroring {@link ai.careerpilot.execution.approval.ApprovalService}'s PENDING/decided
 * state-machine discipline for the review step, without duplicating that class. Dedup requirement:
 * before inserting, checks {@link CompanyConnectorRepository#findByAtsTypeAndCompanyName} so a
 * repeat weekly run never creates a second row for a company already known (whether that existing
 * row is a prior discovery or a manually admin-created connector).
 *
 * <p>Never throws to its caller: each candidate/source pair is isolated in its own try/catch, so
 * one bad candidate or one source outage never aborts the rest of the run — matching {@link
 * ai.careerpilot.jobdiscovery.JobAggregationService#discoverAll()}'s per-provider isolation.
 */
@Service
public class CompanyDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(CompanyDiscoveryService.class);

    public static final String PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    private final CompanyConnectorRepository repo;
    private final List<CompanySource> sources;
    private final CompanyDiscoveryHealthTracker health;
    private final List<String> candidateSlugs;

    public CompanyDiscoveryService(CompanyConnectorRepository repo,
                                    List<CompanySource> sources,
                                    CompanyDiscoveryHealthTracker health,
                                    @Value("${career.discovery.company-agent.candidates:}") String candidatesCsv) {
        this.repo = repo;
        this.sources = sources;
        this.health = health;
        this.candidateSlugs = splitCsv(candidatesCsv);
    }

    public record DiscoveryRunResult(int candidatesProbed, int probesRun, int hits, int inserted, int duplicatesSkipped) {}

    /** Runs one full discovery pass across every configured candidate slug and configured source. */
    public DiscoveryRunResult discoverAll() {
        int probesRun = 0, hits = 0, inserted = 0, duplicatesSkipped = 0;
        List<CompanySource> configured = sources.stream().filter(CompanySource::isConfigured).toList();
        if (configured.isEmpty() || candidateSlugs.isEmpty()) {
            log.debug("company-discovery: skipping run (configuredSources={}, candidates={})",
                    configured.size(), candidateSlugs.size());
            return new DiscoveryRunResult(candidateSlugs.size(), 0, 0, 0, 0);
        }
        for (String slug : candidateSlugs) {
            for (CompanySource source : configured) {
                try {
                    probesRun++;
                    long start = System.currentTimeMillis();
                    Optional<DiscoveredCandidate> result = source.probe(slug);
                    long latency = System.currentTimeMillis() - start;
                    if (result.isEmpty()) {
                        health.recordMiss(source.name(), latency);
                        continue;
                    }
                    health.recordHit(source.name(), latency);
                    hits++;
                    if (isDuplicate(result.get())) {
                        duplicatesSkipped++;
                        continue;
                    }
                    insert(result.get(), source.name());
                    inserted++;
                } catch (Exception e) {
                    log.warn("company-discovery: probe failed source={} slug={}: {}", source.name(), slug, e.toString());
                    health.recordFailure(source.name(), 0, e.toString());
                }
            }
        }
        log.info("company-discovery run complete: candidates={} probesRun={} hits={} inserted={} duplicatesSkipped={}",
                candidateSlugs.size(), probesRun, hits, inserted, duplicatesSkipped);
        return new DiscoveryRunResult(candidateSlugs.size(), probesRun, hits, inserted, duplicatesSkipped);
    }

    @Transactional
    boolean isDuplicate(DiscoveredCandidate candidate) {
        return repo.findByAtsTypeAndCompanyName(candidate.atsType(), candidate.companyName()).isPresent();
    }

    @Transactional
    void insert(DiscoveredCandidate candidate, String sourceName) {
        CompanyConnector connector = CompanyConnector.builder()
                .companyName(candidate.companyName())
                .atsType(candidate.atsType())
                .careerUrl(candidate.careerUrl())
                .tenant(candidate.tenant())
                .cluster(candidate.cluster())
                .site(candidate.site())
                .enabled(false)
                .healthStatus("NEVER_RUN")
                .discoveryStatus(PENDING_APPROVAL)
                .discoveredAt(Instant.now())
                .discoveredBy(sourceName + "-probe")
                .build();
        repo.save(connector);
    }

    /** Flip a pending-approval discovery to APPROVED. Guards against re-deciding an already-terminal row. */
    @Transactional
    public Optional<CompanyConnector> approve(UUID id) {
        return repo.findById(id).map(c -> {
            if (APPROVED.equals(c.getDiscoveryStatus()) || REJECTED.equals(c.getDiscoveryStatus())) {
                return c; // already decided — no-op, do not re-decide a terminal discovery
            }
            c.setDiscoveryStatus(APPROVED);
            c.setEnabled(true);
            return repo.save(c);
        });
    }

    /** Flip a pending-approval discovery to REJECTED. Guards against re-deciding an already-terminal row. */
    @Transactional
    public Optional<CompanyConnector> reject(UUID id) {
        return repo.findById(id).map(c -> {
            if (APPROVED.equals(c.getDiscoveryStatus()) || REJECTED.equals(c.getDiscoveryStatus())) {
                return c;
            }
            c.setDiscoveryStatus(REJECTED);
            c.setEnabled(false);
            return repo.save(c);
        });
    }

    public List<CompanyConnector> pendingApproval() {
        return repo.findByDiscoveryStatus(PENDING_APPROVAL);
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
