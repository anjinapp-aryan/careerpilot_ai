package ai.careerpilot.service.copilot;

import ai.careerpilot.service.CareerContextRetriever.*;
import ai.careerpilot.security.AuthenticatedUser;

import java.util.*;

/**
 * Context passed to skill handlers. Contains user data and tracks which data sources
 * are being used for source attribution in responses.
 */
public class SkillContext {
    private final AuthenticatedUser user;
    private final String message;
    private final String contextId;
    private final String page;

    private ResumeContext resume;
    private JobContext job;
    private ApplicationContext application;
    private WorkflowContext workflow;
    private UserProfileContext userProfile;

    private final Set<String> sources = new LinkedHashSet<>();

    public SkillContext(AuthenticatedUser user, String message, String contextId, String page) {
        this.user = user;
        this.message = message;
        this.contextId = contextId;
        this.page = page;
    }

    public AuthenticatedUser user() { return user; }
    public String message() { return message; }
    public String contextId() { return contextId; }
    public String page() { return page; }

    public ResumeContext resume() { return resume; }
    public void resume(ResumeContext r) { this.resume = r; if (r != null) sources.add("Resume: " + r.filename()); }

    public JobContext job() { return job; }
    public void job(JobContext j) { this.job = j; if (j != null) sources.add("Job: " + j.title() + " @ " + j.company()); }

    public ApplicationContext application() { return application; }
    public void application(ApplicationContext a) { this.application = a; if (a != null) sources.add("Application: " + a.status()); }

    public WorkflowContext workflow() { return workflow; }
    public void workflow(WorkflowContext w) { this.workflow = w; if (w != null) sources.add("Workflow: " + w.status()); }

    public UserProfileContext userProfile() { return userProfile; }
    public void userProfile(UserProfileContext p) { this.userProfile = p; if (p != null) sources.add("User Profile"); }

    public Set<String> sources() { return sources; }
    public void addSource(String source) { sources.add(source); }
    public String sourcesBlock() {
        return sources.isEmpty() ? "" : "\n\n---\n**Sources:** " + String.join(", ", sources);
    }
}
