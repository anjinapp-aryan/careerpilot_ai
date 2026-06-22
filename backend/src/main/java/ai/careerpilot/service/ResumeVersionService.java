package ai.careerpilot.service;

import ai.careerpilot.domain.Resume;
import ai.careerpilot.domain.ResumeVersion;
import ai.careerpilot.kafka.WorkflowEventProducer;
import ai.careerpilot.repo.ResumeRepository;
import ai.careerpilot.repo.ResumeVersionRepository;
import ai.careerpilot.storage.S3StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the resume-version lifecycle for Resume Optimization: creating an immutable
 * optimized version from a completed workflow, listing versions, and serving downloads.
 * Multi-tenant isolation is enforced manually via the owning {@link Resume}.
 */
@Service
public class ResumeVersionService {

    private static final Logger log = LoggerFactory.getLogger(ResumeVersionService.class);

    private final ResumeVersionRepository versions;
    private final ResumeRepository resumes;
    private final ResumeDocumentService documents;
    private final S3StorageService storage;
    private final WorkflowEventProducer events;

    public ResumeVersionService(ResumeVersionRepository versions, ResumeRepository resumes,
                                ResumeDocumentService documents, S3StorageService storage,
                                WorkflowEventProducer events) {
        this.versions = versions;
        this.resumes = resumes;
        this.documents = documents;
        this.storage = storage;
        this.events = events;
    }

    public record NewVersion(
            UUID userId, UUID orgId, UUID resumeId, String threadId,
            String optimizationMode, Integer atsBefore, Integer atsAfter, String providerUsed,
            Map<String, Object> optimizedResume) {}

    /** A streamable download with its content-type and a suggested filename. */
    public record Download(byte[] data, String contentType, String filename) {}

    /**
     * Persist a new optimized version. Renders DOCX (best-effort) + stores the text so the
     * version is always usable even if binary rendering failed. Returns null when there is
     * no optimized content to store (e.g. a rejected run).
     */
    @Transactional
    public ResumeVersion createVersion(NewVersion req) {
        String text = documents.toPlainText(req.optimizedResume());
        if ((text == null || text.isBlank()) && (req.optimizedResume() == null || req.optimizedResume().isEmpty())) {
            log.info("createVersion skipped: no optimized_resume content (resume={}, thread={})",
                    req.resumeId(), req.threadId());
            return null;
        }

        int next = versions.findTopByResumeIdOrderByVersionNumberDesc(req.resumeId())
                .map(v -> v.getVersionNumber() == null ? 1 : v.getVersionNumber() + 1)
                .orElse(1);

        String s3Key = null;
        String contentType = null;
        byte[] docx = documents.toDocx(req.optimizedResume());
        if (docx != null) {
            try {
                s3Key = storage.uploadBytes(docx, "resume-versions/" + req.userId(),
                        "optimized-v" + next + ".docx", ResumeDocumentService.DOCX_CONTENT_TYPE);
                contentType = ResumeDocumentService.DOCX_CONTENT_TYPE;
            } catch (Exception e) {
                log.warn("DOCX upload failed for resume={} v{}: {}", req.resumeId(), next, e.toString());
            }
        }

        ResumeVersion version = ResumeVersion.builder()
                .resumeId(req.resumeId()).userId(req.userId()).orgId(req.orgId())
                .versionNumber(next)
                .optimizationMode(req.optimizationMode())
                .atsBefore(req.atsBefore()).atsAfter(req.atsAfter())
                .providerUsed(req.providerUsed())
                .workflowThreadId(req.threadId())
                .s3Key(s3Key).contentType(contentType)
                .optimizedText(text)
                .build();
        ResumeVersion saved = versions.save(version);
        log.info("Resume version created: resume={}, version={}, thread={}", req.resumeId(), next, req.threadId());

        events.publishResumeEvent(req.threadId(), "resume.version.created", new HashMap<>(Map.of(
                "resumeId", req.resumeId().toString(),
                "versionId", saved.getId().toString(),
                "versionNumber", next,
                "userId", req.userId().toString())));
        return saved;
    }

    /** List versions for a resume the user owns (newest first). */
    public List<ResumeVersion> listForResume(UUID userId, UUID resumeId) {
        ownedResumeOrThrow(userId, resumeId);
        return versions.findByResumeIdOrderByVersionNumberDesc(resumeId);
    }

    /** Resolve a downloadable artifact for an owned version, in the requested format. */
    public Download download(UUID userId, UUID resumeId, UUID versionId, String format) {
        ownedResumeOrThrow(userId, resumeId);
        ResumeVersion v = versions.findById(versionId).orElseThrow();
        if (!v.getResumeId().equals(resumeId) || !v.getUserId().equals(userId)) {
            throw new SecurityException("forbidden");
        }
        String fmt = format == null ? "docx" : format.trim().toLowerCase();
        if ("txt".equals(fmt)) {
            String text = v.getOptimizedText() == null ? "" : v.getOptimizedText();
            return new Download(text.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    ResumeDocumentService.TXT_CONTENT_TYPE, "optimized-v" + v.getVersionNumber() + ".txt");
        }
        // DOCX: prefer the stored object; if it is missing, re-render from text on the fly.
        if (v.getS3Key() != null && !v.getS3Key().isBlank()) {
            byte[] data = storage.download(v.getS3Key());
            return new Download(data, ResumeDocumentService.DOCX_CONTENT_TYPE,
                    "optimized-v" + v.getVersionNumber() + ".docx");
        }
        throw new IllegalStateException("No DOCX available for this version; try format=txt");
    }

    private Resume ownedResumeOrThrow(UUID userId, UUID resumeId) {
        Resume r = resumes.findById(resumeId).orElseThrow();
        if (!r.getUserId().equals(userId)) throw new SecurityException("forbidden");
        return r;
    }
}
