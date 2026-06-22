package ai.careerpilot.api;

import ai.careerpilot.api.dto.JobRecommendationDtos.RecommendedJobsResponse;
import ai.careerpilot.domain.Job;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.service.JobRecommendationService;
import ai.careerpilot.service.JobService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobs;
    private final JobRecommendationService recommendations;

    public JobController(JobService jobs, JobRecommendationService recommendations) {
        this.jobs = jobs;
        this.recommendations = recommendations;
    }

    @GetMapping
    public Page<Job> search(AuthenticatedUser user,
                            @RequestParam(required = false) String q,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "20") int size) {
        // Multi-tenant: filter by org
        return jobs.search(user.orgId(), q, page, size);
    }

    @PostMapping
    public Job create(AuthenticatedUser user, @RequestBody Job job) {
        return jobs.create(user.orgId(), job);
    }

    @GetMapping("/{id}")
    public Job get(AuthenticatedUser user, @PathVariable UUID id) {
        // Multi-tenant: verify ownership
        return jobs.get(user.orgId(), id);
    }

    /** Stage 1 Recommended Jobs: deterministic skill/role/location scoring, no AI call. */
    @GetMapping("/recommended")
    public RecommendedJobsResponse recommended(AuthenticatedUser user,
                                               @RequestParam(defaultValue = "10") int limit) {
        return recommendations.recommend(user.userId(), user.orgId(), limit);
    }
}
