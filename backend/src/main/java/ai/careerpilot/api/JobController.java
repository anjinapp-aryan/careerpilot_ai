package ai.careerpilot.api;

import ai.careerpilot.domain.Job;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.service.JobService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobs;

    public JobController(JobService jobs) { this.jobs = jobs; }

    @GetMapping
    public Page<Job> search(@RequestParam(required = false) String q,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "20") int size) {
        return jobs.search(q, page, size);
    }

    @PostMapping
    public Job create(AuthenticatedUser user, @RequestBody Job job) {
        return jobs.create(user.orgId(), job);
    }

    @GetMapping("/{id}")
    public Job get(@PathVariable UUID id) {
        return jobs.get(id);
    }
}
