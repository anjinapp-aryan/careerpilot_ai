package ai.careerpilot.service;

import ai.careerpilot.domain.Application;
import ai.careerpilot.repo.ApplicationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ApplicationService {

    private final ApplicationRepository apps;

    public ApplicationService(ApplicationRepository apps) { this.apps = apps; }

    @Transactional
    public Application create(UUID userId, UUID orgId, Application body) {
        body.setUserId(userId);
        body.setOrgId(orgId);
        if (body.getStatus() == null) body.setStatus("SAVED");
        return apps.save(body);
    }

    @Transactional
    public Application updateStatus(UUID userId, UUID id, String status, String notes) {
        Application a = apps.findById(id).orElseThrow();
        if (!a.getUserId().equals(userId)) throw new SecurityException("forbidden");
        if (status != null) a.setStatus(status);
        if (notes != null) a.setNotes(notes);
        return a;
    }

    /**
     * Application Command Center — additive PATCH support for favorite/priority/archived on top of
     * the existing status/notes fields. Any field left {@code null} in the body is left untouched,
     * so the existing "just move a status" and "just edit notes" call shapes keep working byte-for-byte.
     */
    @Transactional
    public Application updateFields(UUID userId, UUID id, String status, String notes,
                                    Boolean favorite, String priority, Boolean archived) {
        Application a = apps.findById(id).orElseThrow();
        if (!a.getUserId().equals(userId)) throw new SecurityException("forbidden");
        if (status != null) a.setStatus(status);
        if (notes != null) a.setNotes(notes);
        if (favorite != null) a.setFavorite(favorite);
        if (priority != null) a.setPriority(priority);
        if (archived != null) a.setArchived(archived);
        return a;
    }

    /** Application Command Center — bulk "NEXT_ACTION" support: sets an in-app due date/action. */
    @Transactional
    public Application updateNextAction(UUID userId, UUID id, String nextAction, Instant nextActionAt) {
        Application a = apps.findById(id).orElseThrow();
        if (!a.getUserId().equals(userId)) throw new SecurityException("forbidden");
        a.setNextAction(nextAction);
        a.setNextActionAt(nextActionAt);
        return a;
    }

    /** Application Command Center — bulk "RESUME" support: reassigns resumeId only, never triggers AI. */
    @Transactional
    public Application reassignResume(UUID userId, UUID id, UUID resumeId) {
        Application a = apps.findById(id).orElseThrow();
        if (!a.getUserId().equals(userId)) throw new SecurityException("forbidden");
        a.setResumeId(resumeId);
        return a;
    }

    public List<Application> listForUser(UUID userId) {
        return apps.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public long countByStatus(UUID userId, String status) {
        return apps.countByUserIdAndStatus(userId, status);
    }

    public Application getOwned(UUID userId, UUID id) {
        Application a = apps.findById(id).orElseThrow();
        if (!a.getUserId().equals(userId)) throw new SecurityException("forbidden");
        return a;
    }
}
