package ai.careerpilot.service;

import ai.careerpilot.domain.Resume;
import ai.careerpilot.repo.ResumeRepository;
import ai.careerpilot.service.profile.event.ResumeChangedEvent;
import ai.careerpilot.storage.S3StorageService;
import org.apache.tika.Tika;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

@Service
public class ResumeService {

    private final ResumeRepository resumes;
    private final S3StorageService storage;
    private final ApplicationEventPublisher events;
    private final Tika tika = new Tika();

    public ResumeService(ResumeRepository resumes, S3StorageService storage,
                         ApplicationEventPublisher events) {
        this.resumes = resumes;
        this.storage = storage;
        this.events = events;
    }

    @Transactional
    public Resume upload(UUID userId, UUID orgId, MultipartFile file) throws IOException {
        String s3Key = storage.upload(file, "resumes/" + userId);
        String parsed;
        try (InputStream in = file.getInputStream()) {
            parsed = tika.parseToString(in);
        } catch (Exception e) {
            parsed = "";
        }
        Resume r = Resume.builder()
                .userId(userId).orgId(orgId)
                .filename(file.getOriginalFilename())
                .s3Key(s3Key)
                .contentType(file.getContentType())
                .sizeBytes(file.getSize())
                .parsedText(parsed)
                .build();
        Resume saved = resumes.save(r);
        // Decoupled: the Candidate Profile module (if enabled) regenerates after commit, async.
        // Failure there can never affect this upload — see CandidateProfileEventListener.
        events.publishEvent(ResumeChangedEvent.uploaded(userId, saved.getId()));
        return saved;
    }

    public List<Resume> listForUser(UUID userId) {
        return resumes.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public Resume getOrThrow(UUID id, UUID userId) {
        Resume r = resumes.findById(id).orElseThrow();
        if (!r.getUserId().equals(userId)) throw new SecurityException("forbidden");
        return r;
    }
}
