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
    private ai.careerpilot.story.api.StoryCopilotContextService.StoryCopilotContext story;
    private ai.careerpilot.submission.api.SubmissionCopilotContextService.SubmissionCopilotContext submission;

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

    private Map<String, Object> jobDiscoveryHealth;
    public Map<String, Object> jobDiscoveryHealth() { return jobDiscoveryHealth; }
    public void jobDiscoveryHealth(Map<String, Object> h) {
        this.jobDiscoveryHealth = h;
        if (h != null) sources.add("Job Discovery Providers");
    }

    public ai.careerpilot.story.api.StoryCopilotContextService.StoryCopilotContext story() { return story; }
    public void story(ai.careerpilot.story.api.StoryCopilotContextService.StoryCopilotContext s) {
        this.story = s;
        if (s != null && s.enabled()) sources.add("STAR Story Intelligence");
    }

    public ai.careerpilot.submission.api.SubmissionCopilotContextService.SubmissionCopilotContext submission() { return submission; }
    public void submission(ai.careerpilot.submission.api.SubmissionCopilotContextService.SubmissionCopilotContext s) {
        this.submission = s;
        if (s != null && s.enabled()) sources.add("Application Submission Pipeline");
    }

    private ai.careerpilot.applications.dto.ApplicationCardDtos.ApplicationCardResponse applicationCommandCenter;
    public ai.careerpilot.applications.dto.ApplicationCardDtos.ApplicationCardResponse applicationCommandCenter() { return applicationCommandCenter; }
    public void applicationCommandCenter(ai.careerpilot.applications.dto.ApplicationCardDtos.ApplicationCardResponse a) {
        this.applicationCommandCenter = a;
        if (a != null) sources.add("Application Command Center");
    }

    // Gap B — Offer Intelligence & Salary Negotiation (additive; no other skill sets this).
    private java.util.List<ai.careerpilot.offer.Offer> offers;
    public java.util.List<ai.careerpilot.offer.Offer> offers() { return offers; }
    public void offers(java.util.List<ai.careerpilot.offer.Offer> o) {
        this.offers = o;
        if (o != null && !o.isEmpty()) sources.add("Offer Intelligence");
    }

    private CareerContextRetriever.CandidateProfileContext candidateProfile;
    public CareerContextRetriever.CandidateProfileContext candidateProfile() { return candidateProfile; }
    public void candidateProfile(CareerContextRetriever.CandidateProfileContext p) {
        this.candidateProfile = p;
        if (p != null) sources.add("Candidate Profile");
    }

    /**
     * Rendered once, centrally, in {@code CopilotService} — every skill gets this appended to
     * its context block automatically, so no individual handler has to remember to ask for it.
     * Returns {@code ""} when there's no profile (dark-flag off or not generated yet), same
     * fail-soft convention as {@link #sourcesBlock()}.
     */
    public String candidateProfileBlock() {
        if (candidateProfile == null) return "";
        var p = candidateProfile;
        StringBuilder sb = new StringBuilder("\n\nCANDIDATE PROFILE (known — do not ask the user for this again)\n");
        if (p.currentRole() != null) sb.append("Current role: ").append(p.currentRole()).append("\n");
        if (p.seniorityLevel() != null) sb.append("Seniority: ").append(p.seniorityLevel()).append("\n");
        if (p.yearsExperience() != null) sb.append("Years experience: ").append(p.yearsExperience()).append("\n");
        if (p.targetRolesJson() != null) sb.append("Target roles: ").append(p.targetRolesJson()).append("\n");
        if (p.skillsJson() != null) sb.append("Skills: ").append(p.skillsJson()).append("\n");
        if (p.careerGoalsJson() != null) sb.append("Career goals: ").append(p.careerGoalsJson()).append("\n");
        if (p.preferredCountriesJson() != null) sb.append("Preferred countries: ").append(p.preferredCountriesJson()).append("\n");
        if (p.preferredCitiesJson() != null) sb.append("Preferred cities: ").append(p.preferredCitiesJson()).append("\n");
        if (p.workModesJson() != null) sb.append("Work mode preference: ").append(p.workModesJson()).append("\n");
        if (p.visaRequired() != null) sb.append("Needs visa sponsorship: ").append(p.visaRequired()).append("\n");
        if (p.salaryTarget() != null) sb.append("Salary target: ").append(p.salaryTarget())
                .append(p.salaryCurrency() != null ? " " + p.salaryCurrency() : "").append("\n");
        if (p.excludedRolesJson() != null) sb.append("Roles the candidate has explicitly excluded — never recommend these: ")
                .append(p.excludedRolesJson()).append("\n");
        return sb.toString();
    }

    private List<CareerContextRetriever.MemoryContext> memories;
    public List<CareerContextRetriever.MemoryContext> memories() { return memories; }
    public void memories(List<CareerContextRetriever.MemoryContext> m) {
        this.memories = m;
        if (m != null && !m.isEmpty()) sources.add("Career Decision Memory");
    }

    /**
     * Rendered once, centrally, in {@code CopilotService} — same convention as
     * {@link #candidateProfileBlock()}. Only the ranked top-N memories already selected by
     * {@code CareerMemoryService}, never the full ledger.
     */
    public String memoriesBlock() {
        if (memories == null || memories.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\nWHAT WE'VE LEARNED ABOUT THIS CANDIDATE (from past decisions — reference naturally, don't just list these back)\n");
        for (var m : memories) {
            sb.append("- ").append(m.decisionType());
            if (m.value() != null && !m.value().isBlank()) sb.append(": ").append(m.value());
            if (m.reason() != null && !m.reason().isBlank()) sb.append(" (reason: ").append(m.reason()).append(")");
            sb.append("\n");
        }
        return sb.toString();
    }

    public Set<String> sources() { return sources; }
    public void addSource(String source) { sources.add(source); }
    public String sourcesBlock() {
        return sources.isEmpty() ? "" : "\n\n---\n**Sources:** " + String.join(", ", sources);
    }
}
