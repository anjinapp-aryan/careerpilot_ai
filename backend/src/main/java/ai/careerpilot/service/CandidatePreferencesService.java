package ai.careerpilot.service;

import ai.careerpilot.api.dto.CandidatePreferencesDto;
import ai.careerpilot.domain.CandidatePreferences;
import ai.careerpilot.repo.CandidatePreferencesRepository;
import ai.careerpilot.service.profile.event.PreferencesUpdatedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * CRUD for per-user job preferences. The preference row is keyed by user id, so reads and
 * writes are implicitly user-scoped (no cross-tenant leakage possible).
 */
@Service
public class CandidatePreferencesService {

    private final CandidatePreferencesRepository repo;
    private final ApplicationEventPublisher events;

    public CandidatePreferencesService(CandidatePreferencesRepository repo,
                                       ApplicationEventPublisher events) {
        this.repo = repo;
        this.events = events;
    }

    /** Current preferences, or sensible defaults if the user has never saved any. */
    public CandidatePreferencesDto get(UUID userId) {
        return repo.findById(userId)
                .map(CandidatePreferencesDto::from)
                .orElseGet(CandidatePreferencesDto::defaults);
    }

    @Transactional
    public CandidatePreferencesDto save(UUID userId, CandidatePreferencesDto dto) {
        CandidatePreferences saved = repo.save(dto.toEntity(userId));
        // Decoupled: the Candidate Profile module (if enabled) re-merges the preference snapshot
        // after commit, async, with no LLM call — see CandidateProfileEventListener.
        events.publishEvent(new PreferencesUpdatedEvent(userId));
        return CandidatePreferencesDto.from(saved);
    }

    /** Scorer-facing view; empty preferences when none are saved. */
    public ai.careerpilot.jobdiscovery.JobScoring.PreferenceContext scoringContext(UUID userId) {
        return repo.findById(userId)
                .map(CandidatePreferencesDto::from)
                .orElseGet(CandidatePreferencesDto::defaults)
                .toScoringContext();
    }
}
