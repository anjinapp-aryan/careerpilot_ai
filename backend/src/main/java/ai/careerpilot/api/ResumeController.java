package ai.careerpilot.api;

import ai.careerpilot.api.dto.CandidateProfileDto;
import ai.careerpilot.api.dto.ResumeIntelligenceDtos.ResumeAnalysisHistoryEntryDto;
import ai.careerpilot.api.dto.ResumeIntelligenceDtos.ResumeAnalysisStatusDto;
import ai.careerpilot.api.dto.ResumeIntelligenceDtos.ResumeDashboardEntryDto;
import ai.careerpilot.api.dto.ResumeVersionDtos.ResumeVersionResponse;
import ai.careerpilot.domain.Resume;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.service.ResumeService;
import ai.careerpilot.service.ResumeVersionService;
import ai.careerpilot.service.profile.ResumeIntelligenceCenterService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/resumes")
public class ResumeController {

    private final ResumeService resumes;
    private final ResumeVersionService versions;
    private final ResumeIntelligenceCenterService intelligence;

    public ResumeController(ResumeService resumes, ResumeVersionService versions,
                            ResumeIntelligenceCenterService intelligence) {
        this.resumes = resumes;
        this.versions = versions;
        this.intelligence = intelligence;
    }

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<Resume> upload(AuthenticatedUser user,
                                         @RequestParam("file") MultipartFile file) throws IOException {
        return ResponseEntity.ok(resumes.upload(user.userId(), user.orgId(), file));
    }

    @GetMapping
    public List<Resume> list(AuthenticatedUser user) {
        return resumes.listForUser(user.userId());
    }

    /** Resume Optimization version history (newest first). */
    @GetMapping("/{id}/versions")
    public List<ResumeVersionResponse> versions(AuthenticatedUser user, @PathVariable UUID id) {
        return versions.listForResume(user.userId(), id).stream()
                .map(ResumeVersionResponse::of)
                .toList();
    }

    /** Download an optimized version as DOCX (default) or TXT, streamed with auth. */
    @GetMapping("/{id}/versions/{versionId}/download")
    public ResponseEntity<byte[]> download(AuthenticatedUser user,
                                           @PathVariable UUID id,
                                           @PathVariable UUID versionId,
                                           @RequestParam(defaultValue = "docx") String format) {
        ResumeVersionService.Download d = versions.download(user.userId(), id, versionId, format);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(d.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(d.filename()).build().toString())
                .body(d.data());
    }

    // ── Phase 8.2 — Resume Intelligence Center (dark-shipped: 404 when disabled) ──────────

    /** Every resume with its current analysis status, newest upload first. */
    @GetMapping("/intelligence/dashboard")
    public ResponseEntity<List<ResumeDashboardEntryDto>> dashboard(AuthenticatedUser user) {
        if (!intelligence.isEnabled()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(intelligence.dashboard(user.userId()));
    }

    /** Analyze (or re-analyze) this resume — synchronous, reuses the existing extraction pipeline. */
    @PostMapping("/{id}/analyze")
    public ResponseEntity<ResumeAnalysisStatusDto> analyze(AuthenticatedUser user, @PathVariable UUID id) {
        if (!intelligence.isEnabled()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(intelligence.analyze(user.userId(), id));
    }

    /** Same operation as {@link #analyze} — separate route only because the UI action reads differently. */
    @PostMapping("/{id}/reanalyze")
    public ResponseEntity<ResumeAnalysisStatusDto> reanalyze(AuthenticatedUser user, @PathVariable UUID id) {
        if (!intelligence.isEnabled()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(intelligence.analyze(user.userId(), id));
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<ResumeAnalysisStatusDto> status(AuthenticatedUser user, @PathVariable UUID id) {
        if (!intelligence.isEnabled()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(intelligence.status(user.userId(), id));
    }

    /** The canonical profile, only when this resume is still its current source (404 otherwise). */
    @GetMapping("/{id}/analysis")
    public ResponseEntity<CandidateProfileDto> analysis(AuthenticatedUser user, @PathVariable UUID id) {
        if (!intelligence.isEnabled()) return ResponseEntity.notFound().build();
        return intelligence.analysis(user.userId(), id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<List<ResumeAnalysisHistoryEntryDto>> history(AuthenticatedUser user, @PathVariable UUID id) {
        if (!intelligence.isEnabled()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(intelligence.history(user.userId(), id));
    }
}
