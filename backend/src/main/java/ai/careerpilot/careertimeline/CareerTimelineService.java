package ai.careerpilot.careertimeline;

import ai.careerpilot.domain.CareerDecisionMemory;
import ai.careerpilot.domain.CareerMission;
import ai.careerpilot.domain.CompanyKnowledge;
import ai.careerpilot.domain.CompanyTimelineEvent;
import ai.careerpilot.domain.Resume;
import ai.careerpilot.domain.ResumeTailoring;
import ai.careerpilot.domain.WorkflowRun;
import ai.careerpilot.memory.CareerMemoryService;
import ai.careerpilot.mission.MissionStatus;
import ai.careerpilot.repo.ApplicationTimelineRepository;
import ai.careerpilot.repo.CareerMissionRepository;
import ai.careerpilot.repo.CompanyKnowledgeRepository;
import ai.careerpilot.repo.CompanyTimelineEventRepository;
import ai.careerpilot.repo.InterviewRepository;
import ai.careerpilot.repo.LearningEventRepository;
import ai.careerpilot.repo.ResumeRepository;
import ai.careerpilot.repo.ResumeTailoringRepository;
import ai.careerpilot.repo.WorkflowRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Phase 10H — Career Timeline: a read-only AGGREGATION layer, not a new event store. Every entry
 * this service returns is sourced from an entity/service that already exists and was already
 * persisting real data for its own domain — this class creates zero new tables and duplicates no
 * persistence. It only merges, sorts, and paginates.
 *
 * <p>Eight categories are sourced ({@link CareerTimelineCategory}): Mission, Application, Resume,
 * Learning, Interview, Memory, Company, Workflow. A ninth, "Analytics," was deliberately left out
 * — {@code ApplicationAnalytics}/{@code CareerIntelligence} are raw numeric snapshot series with no
 * discrete "personal best" or "score improved" event ever computed or persisted anywhere in this
 * codebase (confirmed by architecture review). Synthesizing one here would be exactly the kind of
 * fabricated narrative this whole feature exists to avoid, so Analytics is simply absent rather
 * than invented — a client asking for it gets an honestly empty category, never a guessed value.
 *
 * <p>Each source is read independently and wrapped in its own try/catch (mirroring this codebase's
 * established defensive-isolation convention, e.g. {@code ApplicationCardService}) — one source
 * failing (a disabled dependency, a transient DB issue) degrades that category to empty rather than
 * breaking the whole feed.
 *
 * <p>Flag-gated dark by {@code career.timeline.enabled} (default false); with the flag off,
 * {@link #forUser} returns an empty page unconditionally, without touching any repository.
 */
@Service
public class CareerTimelineService {

    private static final Logger log = LoggerFactory.getLogger(CareerTimelineService.class);

    private final CareerMissionRepository missions;
    private final ApplicationTimelineRepository applicationTimelines;
    private final ResumeRepository resumes;
    private final ResumeTailoringRepository resumeTailorings;
    private final LearningEventRepository learningEvents;
    private final InterviewRepository interviews;
    private final CareerMemoryService careerMemory;
    private final CompanyKnowledgeRepository companyKnowledge;
    private final CompanyTimelineEventRepository companyTimelineEvents;
    private final WorkflowRunRepository workflowRuns;

    private final boolean enabled;

    public CareerTimelineService(CareerMissionRepository missions, ApplicationTimelineRepository applicationTimelines,
                                  ResumeRepository resumes, ResumeTailoringRepository resumeTailorings,
                                  LearningEventRepository learningEvents, InterviewRepository interviews,
                                  CareerMemoryService careerMemory, CompanyKnowledgeRepository companyKnowledge,
                                  CompanyTimelineEventRepository companyTimelineEvents, WorkflowRunRepository workflowRuns,
                                  @Value("${career.timeline.enabled:false}") boolean enabled) {
        this.missions = missions;
        this.applicationTimelines = applicationTimelines;
        this.resumes = resumes;
        this.resumeTailorings = resumeTailorings;
        this.learningEvents = learningEvents;
        this.interviews = interviews;
        this.careerMemory = careerMemory;
        this.companyKnowledge = companyKnowledge;
        this.companyTimelineEvents = companyTimelineEvents;
        this.workflowRuns = workflowRuns;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * A page of the merged, sorted (newest-first) career timeline for one user, optionally
     * restricted to a single category. {@code page}/{@code size} are plain in-memory offsets over
     * the merged list (this codebase's established convention for user-scoped feeds — see
     * {@code WorkflowRunRepository.findTop20By...} — is manual list truncation over
     * {@code Pageable}/{@code Page<T>}, not Spring Data paging). Returns one extra row internally
     * to compute {@code hasMore} without a second count query.
     */
    public Page forUser(UUID userId, CareerTimelineCategory category, int page, int size) {
        if (!enabled) return new Page(List.of(), false);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);

        List<CareerTimelineEntry> merged = new ArrayList<>();
        if (category == null || category == CareerTimelineCategory.MISSION) merged.addAll(missionEntries(userId));
        if (category == null || category == CareerTimelineCategory.APPLICATION) merged.addAll(applicationEntries(userId));
        if (category == null || category == CareerTimelineCategory.RESUME) merged.addAll(resumeEntries(userId));
        if (category == null || category == CareerTimelineCategory.LEARNING) merged.addAll(learningEntries(userId));
        if (category == null || category == CareerTimelineCategory.INTERVIEW) merged.addAll(interviewEntries(userId));
        if (category == null || category == CareerTimelineCategory.MEMORY) merged.addAll(memoryEntries(userId));
        if (category == null || category == CareerTimelineCategory.COMPANY) merged.addAll(companyEntries(userId));
        if (category == null || category == CareerTimelineCategory.WORKFLOW) merged.addAll(workflowEntries(userId));

        merged.sort(Comparator.comparing(CareerTimelineEntry::occurredAt, Comparator.nullsLast(Comparator.reverseOrder())));

        int from = Math.min(safePage * safeSize, merged.size());
        int to = Math.min(from + safeSize + 1, merged.size());
        List<CareerTimelineEntry> windowPlusOne = merged.subList(from, to);
        boolean hasMore = windowPlusOne.size() > safeSize;
        List<CareerTimelineEntry> pageItems = hasMore ? windowPlusOne.subList(0, safeSize) : windowPlusOne;
        return new Page(List.copyOf(pageItems), hasMore);
    }

    public record Page(List<CareerTimelineEntry> entries, boolean hasMore) {
    }

    // ── per-source mappers, each isolated so one failing source degrades to empty ──

    private List<CareerTimelineEntry> missionEntries(UUID userId) {
        return safely("mission", () -> {
            List<CareerTimelineEntry> out = new ArrayList<>();
            for (CareerMission m : missions.findByUserIdOrderByCreatedAtDesc(userId)) {
                out.add(new CareerTimelineEntry(m.getId(), CareerTimelineCategory.MISSION, "MISSION_CREATED",
                        "Mission created", truncate(m.getMissionStatement(), 200), m.getCreatedAt(), "SYSTEM", false,
                        m.getId(), null, null, null));
                if (m.getStatus() != null && m.getStatus() != MissionStatus.ACTIVE
                        && m.getUpdatedAt() != null && !m.getUpdatedAt().equals(m.getCreatedAt())) {
                    out.add(new CareerTimelineEntry(m.getId(), CareerTimelineCategory.MISSION,
                            "MISSION_" + m.getStatus().name(), "Mission " + m.getStatus().name().toLowerCase(Locale.ROOT),
                            null, m.getUpdatedAt(), "SYSTEM", false, m.getId(), null, null, null));
                }
            }
            return out;
        });
    }

    private List<CareerTimelineEntry> applicationEntries(UUID userId) {
        return safely("application", () -> applicationTimelines.findByUserIdOrderByOccurredAtDesc(userId).stream()
                .map(t -> new CareerTimelineEntry(t.getId(), CareerTimelineCategory.APPLICATION, t.getEventType(),
                        humanize(t.getEventType()), t.getDetails(), t.getOccurredAt(), t.getEventSource(), null,
                        null, t.getJobId(), null, null))
                .toList());
    }

    private List<CareerTimelineEntry> resumeEntries(UUID userId) {
        return safely("resume", () -> {
            List<CareerTimelineEntry> out = new ArrayList<>();
            for (Resume r : resumes.findByUserIdOrderByCreatedAtDesc(userId)) {
                out.add(new CareerTimelineEntry(r.getId(), CareerTimelineCategory.RESUME, "RESUME_UPLOADED",
                        "Resume uploaded", r.getFilename(), r.getCreatedAt(), "USER", false, null, null, null, null));
            }
            for (ResumeTailoring t : resumeTailorings.findByUserIdOrderByCreatedAtDesc(userId)) {
                String desc = (t.getAtsBefore() != null && t.getAtsAfter() != null)
                        ? ("ATS score: " + t.getAtsBefore() + " → " + t.getAtsAfter()) : null;
                out.add(new CareerTimelineEntry(t.getId(), CareerTimelineCategory.RESUME, "RESUME_TAILORED",
                        "Resume tailored for a job", desc, t.getCreatedAt(), "AI", true, null, t.getJobId(), null, null));
            }
            return out;
        });
    }

    private List<CareerTimelineEntry> learningEntries(UUID userId) {
        return safely("learning", () -> learningEvents.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(e -> new CareerTimelineEntry(e.getId(), CareerTimelineCategory.LEARNING, e.getEventType(),
                        humanize(e.getEventType()), e.getSkills(), e.getCreatedAt(), "SYSTEM", null,
                        null, e.getJobId(), null, e.getCompany()))
                .toList());
    }

    private List<CareerTimelineEntry> interviewEntries(UUID userId) {
        return safely("interview", () -> interviews.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(iv -> new CareerTimelineEntry(iv.getId(), CareerTimelineCategory.INTERVIEW, iv.getInterviewType(),
                        "Interview: " + humanize(iv.getInterviewType()), iv.getResult(),
                        iv.getScheduledAt() != null ? iv.getScheduledAt() : iv.getCreatedAt(), "USER", false,
                        null, iv.getJobId(), null, null))
                .toList());
    }

    private List<CareerTimelineEntry> memoryEntries(UUID userId) {
        return safely("memory", () -> careerMemory.timelineFor(userId).stream()
                .map(this::toMemoryEntry)
                .toList());
    }

    private CareerTimelineEntry toMemoryEntry(CareerDecisionMemory m) {
        String title = m.getValue() != null && !m.getValue().isBlank() ? m.getValue() : humanize(m.getDecisionType());
        return new CareerTimelineEntry(m.getId(), CareerTimelineCategory.MEMORY, m.getDecisionType(), title,
                m.getReason(), m.getCreatedAt(), m.getSource(), Boolean.TRUE.equals(m.getAiGenerated()),
                null, m.getJobId(), null, null);
    }

    private List<CareerTimelineEntry> companyEntries(UUID userId) {
        return safely("company", () -> {
            Map<UUID, String> nameById = new java.util.HashMap<>();
            for (CompanyKnowledge c : companyKnowledge.findByUserIdOrderByUpdatedAtDesc(userId)) {
                nameById.put(c.getId(), c.getCompanyName());
            }
            List<CompanyTimelineEvent> events = companyTimelineEvents.findByUserIdOrderByOccurredAtDesc(userId);
            List<CareerTimelineEntry> out = new ArrayList<>(events.size());
            for (CompanyTimelineEvent e : events) {
                String companyName = nameById.get(e.getCompanyKnowledgeId());
                String title = humanize(e.getEventType()) + (companyName != null ? " — " + companyName : "");
                out.add(new CareerTimelineEntry(e.getId(), CareerTimelineCategory.COMPANY, e.getEventType(), title,
                        e.getDetail(), e.getOccurredAt(), "SYSTEM", null, null, null,
                        e.getCompanyKnowledgeId(), companyName));
            }
            return out;
        });
    }

    private List<CareerTimelineEntry> workflowEntries(UUID userId) {
        return safely("workflow", () -> {
            List<CareerTimelineEntry> out = new ArrayList<>();
            for (WorkflowRun r : workflowRuns.findTop20ByUserIdOrderByCreatedAtDesc(userId)) {
                String label = r.getTargetRole() != null ? " for " + r.getTargetRole() : "";
                out.add(new CareerTimelineEntry(r.getId(), CareerTimelineCategory.WORKFLOW, "WORKFLOW_STARTED",
                        "AI workflow started" + label, null, r.getCreatedAt(), "AI", true, null, null, null, null));
                boolean terminal = r.getStatus() != null
                        && (r.getStatus().equals("COMPLETED") || r.getStatus().equals("ERROR") || r.getStatus().equals("REJECTED"));
                if (terminal && r.getUpdatedAt() != null && !r.getUpdatedAt().equals(r.getCreatedAt())) {
                    out.add(new CareerTimelineEntry(r.getId(), CareerTimelineCategory.WORKFLOW,
                            "WORKFLOW_" + r.getStatus(), "AI workflow " + r.getStatus().toLowerCase(Locale.ROOT),
                            r.getErrorMessage(), r.getUpdatedAt(), "AI", true, null, null, null, null));
                }
            }
            return out;
        });
    }

    private List<CareerTimelineEntry> safely(String sourceName, Supplier<List<CareerTimelineEntry>> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("CAREER_TIMELINE source={} failed: {}", sourceName, e.toString());
            return List.of();
        }
    }

    private static String humanize(String raw) {
        if (raw == null || raw.isBlank()) return "Event";
        String spaced = raw.replace('_', ' ').toLowerCase(Locale.ROOT);
        return spaced.substring(0, 1).toUpperCase(Locale.ROOT) + spaced.substring(1);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
