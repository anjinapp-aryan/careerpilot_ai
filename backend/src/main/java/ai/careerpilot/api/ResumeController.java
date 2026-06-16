package ai.careerpilot.api;

import ai.careerpilot.domain.Resume;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.service.ResumeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/resumes")
public class ResumeController {

    private final ResumeService resumes;

    public ResumeController(ResumeService resumes) {
        this.resumes = resumes;
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
}
