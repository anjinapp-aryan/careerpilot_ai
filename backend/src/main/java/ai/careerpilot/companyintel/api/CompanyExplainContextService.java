package ai.careerpilot.companyintel.api;

import ai.careerpilot.companyintel.CompanyInsightGenerator;
import ai.careerpilot.companyintel.CompanyInterviewIntelligenceService;
import ai.careerpilot.companyintel.CompanyKnowledgeService;
import ai.careerpilot.companyintel.CompanySimilarityService;
import ai.careerpilot.companyintel.analyzer.CompanyInsight;
import ai.careerpilot.domain.CompanyKnowledge;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 7.13 — assembles the grounded company context the Copilot company skills render into their
 * prompts. Reads only persisted knowledge (sections, scores, insights, similarity) — the handlers'
 * prompts forbid inventing anything beyond it. Gated by BOTH {@code company.knowledge.enabled} and
 * {@code company.copilot.enabled}: dark returns an empty context and the skills answer "not enabled".
 */
@Service
public class CompanyExplainContextService {

    private final CompanyKnowledgeService knowledge;
    private final CompanyInsightGenerator insights;
    private final CompanySimilarityService similarity;
    private final CompanyInterviewIntelligenceService interviewIntelligence;
    private final boolean copilotEnabled;

    public CompanyExplainContextService(CompanyKnowledgeService knowledge,
                                        CompanyInsightGenerator insights,
                                        CompanySimilarityService similarity,
                                        CompanyInterviewIntelligenceService interviewIntelligence,
                                        @Value("${company.copilot.enabled:false}") boolean copilotEnabled) {
        this.knowledge = knowledge;
        this.insights = insights;
        this.similarity = similarity;
        this.interviewIntelligence = interviewIntelligence;
        this.copilotEnabled = copilotEnabled;
    }

    public boolean isEnabled() {
        return copilotEnabled && knowledge.isEnabled();
    }

    public record CompanyDetail(CompanyKnowledge company, Map<String, String> sections,
                                List<CompanyInsight> insights,
                                List<CompanySimilarityService.SimilarCompany> similar,
                                /** Phase 7.17.2 — real interview data for this company, when observed. Null when never enabled/no data. */
                                Map<String, Object> interviewIntelligence) {}

    public record CompanyExplainContext(boolean enabled, List<CompanyKnowledge> knownCompanies,
                                        List<CompanyDetail> mentioned) {
        public static CompanyExplainContext dark() {
            return new CompanyExplainContext(false, List.of(), List.of());
        }
    }

    /** Context for one request: the user's known companies plus details for any mentioned by name. */
    public CompanyExplainContext get(UUID userId, String message) {
        if (!isEnabled()) return CompanyExplainContext.dark();
        List<CompanyKnowledge> known = knowledge.search(userId, null);
        List<CompanyDetail> mentioned = known.stream()
                .filter(c -> isMentioned(message, c))
                .limit(3)
                .map(c -> new CompanyDetail(c, knowledge.sections(c), insights.generate(c),
                        similarity.isEnabled() ? similarity.similarTo(userId, c.getId(), 5) : List.of(),
                        interviewIntelligence.forCompany(userId, c.getCompanyName()).orElse(null)))
                .toList();
        return new CompanyExplainContext(true, known.stream().limit(25).toList(), mentioned);
    }

    private static boolean isMentioned(String message, CompanyKnowledge company) {
        if (message == null || message.isBlank()) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains(company.getNormalizedName())
                || lower.contains(company.getCompanyName().toLowerCase(Locale.ROOT));
    }
}
