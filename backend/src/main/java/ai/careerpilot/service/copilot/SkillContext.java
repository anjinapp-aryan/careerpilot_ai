package ai.careerpilot.service.copilot;

import ai.careerpilot.service.CareerContextRetriever;
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
    private CareerContextRetriever.DailyDiscoveryContext dailyDiscovery;
    private ai.careerpilot.learning.api.LearningExplainContextService.LearningExplainContext learning;
    private ai.careerpilot.autopilot.api.AutopilotExplainContextService.AutopilotExplainContext autopilot;
    private ai.careerpilot.packageintel.api.PackageExplainContextService.PackageExplainContext applicationPackage;
    private ai.careerpilot.review.api.ReviewExplainContextService.ReviewExplainContext applicationReview;
    private ai.careerpilot.companyintel.api.CompanyExplainContextService.CompanyExplainContext companyIntel;

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

    public CareerContextRetriever.DailyDiscoveryContext dailyDiscovery() { return dailyDiscovery; }
    public void dailyDiscovery(CareerContextRetriever.DailyDiscoveryContext d) {
        this.dailyDiscovery = d;
        if (d != null) sources.add("Daily Discovery");
    }

    public ai.careerpilot.learning.api.LearningExplainContextService.LearningExplainContext learning() { return learning; }
    public void learning(ai.careerpilot.learning.api.LearningExplainContextService.LearningExplainContext l) {
        this.learning = l;
        if (l != null) sources.add("Learning Engine");
    }

    public ai.careerpilot.autopilot.api.AutopilotExplainContextService.AutopilotExplainContext autopilot() { return autopilot; }
    public void autopilot(ai.careerpilot.autopilot.api.AutopilotExplainContextService.AutopilotExplainContext a) {
        this.autopilot = a;
        if (a != null) sources.add("Application Agent");
    }

    public ai.careerpilot.packageintel.api.PackageExplainContextService.PackageExplainContext applicationPackage() { return applicationPackage; }
    public void applicationPackage(ai.careerpilot.packageintel.api.PackageExplainContextService.PackageExplainContext p) {
        this.applicationPackage = p;
        if (p != null) sources.add("Application Package");
    }

    public ai.careerpilot.review.api.ReviewExplainContextService.ReviewExplainContext applicationReview() { return applicationReview; }
    public void applicationReview(ai.careerpilot.review.api.ReviewExplainContextService.ReviewExplainContext r) {
        this.applicationReview = r;
        if (r != null) sources.add("AI Review");
    }

    public ai.careerpilot.companyintel.api.CompanyExplainContextService.CompanyExplainContext companyIntel() { return companyIntel; }
    public void companyIntel(ai.careerpilot.companyintel.api.CompanyExplainContextService.CompanyExplainContext c) {
        this.companyIntel = c;
        if (c != null && c.enabled()) sources.add("Company Intelligence");
    }

    public Set<String> sources() { return sources; }
    public void addSource(String source) { sources.add(source); }
    public String sourcesBlock() {
        return sources.isEmpty() ? "" : "\n\n---\n**Sources:** " + String.join(", ", sources);
    }
}
