package ai.careerpilot.companyintel;

import ai.careerpilot.companyintel.analyzer.*;
import ai.careerpilot.domain.CompanyKnowledge;
import ai.careerpilot.domain.CompanyRelationship;
import ai.careerpilot.domain.CompanyTimelineEvent;
import ai.careerpilot.repo.CompanyRelationshipRepository;
import ai.careerpilot.repo.CompanyTimelineEventRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Phase 7.13 — runs every deterministic analyzer over one company's knowledge/graph/timeline and
 * returns the combined insight list. Analyzers that have nothing observed contribute nothing —
 * an empty list means "we know nothing yet", never fabricated filler.
 */
@Component
public class CompanyInsightGenerator {

    private final CompanyRelationshipRepository relationships;
    private final CompanyTimelineEventRepository events;
    private final KnowledgeAggregator aggregator;
    private final TechnologyAnalyzer technology;
    private final HiringPatternAnalyzer hiringPattern;
    private final CultureAnalyzer culture;
    private final GrowthAnalyzer growth;
    private final SkillDemandAnalyzer skillDemand;
    private final CompanyTrendAnalyzer trend;

    public CompanyInsightGenerator(CompanyRelationshipRepository relationships,
                                   CompanyTimelineEventRepository events,
                                   KnowledgeAggregator aggregator,
                                   TechnologyAnalyzer technology,
                                   HiringPatternAnalyzer hiringPattern,
                                   CultureAnalyzer culture,
                                   GrowthAnalyzer growth,
                                   SkillDemandAnalyzer skillDemand,
                                   CompanyTrendAnalyzer trend) {
        this.relationships = relationships;
        this.events = events;
        this.aggregator = aggregator;
        this.technology = technology;
        this.hiringPattern = hiringPattern;
        this.culture = culture;
        this.growth = growth;
        this.skillDemand = skillDemand;
        this.trend = trend;
    }

    public List<CompanyInsight> generate(CompanyKnowledge company) {
        Map<String, String> sections = aggregator.parse(company.getKnowledge());
        List<CompanyRelationship> edges = relationships.findByCompanyKnowledgeId(company.getId());
        List<CompanyTimelineEvent> timeline = events.findByCompanyKnowledgeIdOrderByOccurredAtDesc(company.getId());

        List<CompanyInsight> insights = new ArrayList<>();
        technology.analyze(edges).ifPresent(insights::add);
        hiringPattern.analyze(edges, timeline).ifPresent(insights::add);
        culture.analyze(sections).ifPresent(insights::add);
        growth.analyze(sections, edges).ifPresent(insights::add);
        skillDemand.analyze(edges).ifPresent(insights::add);
        trend.analyze(timeline).ifPresent(insights::add);
        return insights;
    }
}
