package ai.careerpilot.autopilot.provider;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Phase 7.4 — recognizes LinkedIn job URLs (Easy Apply). Submission not integrated: LinkedIn Easy
 * Apply requires an authenticated in-session browser flow, which lives behind this abstraction and
 * does not exist, so this always routes to human review.
 */
@Component
public class LinkedInEasyApplyProvider extends AbstractAtsProvider {
    @Override public String name() { return "linkedin-easy-apply"; }
    @Override protected List<String> hostPatterns() { return List.of("linkedin.com/jobs", "linkedin.com/job"); }
}
