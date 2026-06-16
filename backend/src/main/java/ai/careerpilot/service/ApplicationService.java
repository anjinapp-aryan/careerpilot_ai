package ai.careerpilot.service;

import ai.careerpilot.domain.Application;
import ai.careerpilot.repo.ApplicationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        a.setStatus(status);
        if (notes != null) a.setNotes(notes);
        return a;
    }

    public List<Application> listForUser(UUID userId) {
        return apps.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public long countByStatus(UUID userId, String status) {
        return apps.countByUserIdAndStatus(userId, status);
    }
}
