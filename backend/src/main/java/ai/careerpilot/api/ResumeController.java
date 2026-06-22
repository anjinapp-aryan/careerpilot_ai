package ai.careerpilot.api;

import ai.careerpilot.api.dto.ResumeVersionDtos.ResumeVersionResponse;
import ai.careerpilot.domain.Resume;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.service.ResumeService;
import ai.careerpilot.service.ResumeVersionService;
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

    public ResumeController(ResumeService resumes, ResumeVersionService versions) {
        this.resumes = resumes;
        this.versions = versions;
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
}
