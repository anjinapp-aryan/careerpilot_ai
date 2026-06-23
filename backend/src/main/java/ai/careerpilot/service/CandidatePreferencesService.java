package ai.careerpilot.service;

import ai.careerpilot.api.dto.CandidatePreferencesDto;
import ai.careerpilot.domain.CandidatePreferences;
import ai.careerpilot.repo.CandidatePreferencesRepository;
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

    public CandidatePreferencesService(CandidatePreferencesRepository repo) {
        this.repo = repo;
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
