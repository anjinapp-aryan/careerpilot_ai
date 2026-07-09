package ai.careerpilot.companyintel.analyzer;

import ai.careerpilot.domain.CompanyRelationship;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Phase 7.13 — summarizes a company's observed technology stack from TECHNOLOGY/SKILL graph edges. */
@Component
public class TechnologyAnalyzer {

    public Optional<CompanyInsight> analyze(List<CompanyRelationship> edges) {
        List<CompanyRelationship> tech = edges.stream()
                .filter(e -> CompanyRelationship.TYPE_TECHNOLOGY.equals(e.getRelationType())
                        || CompanyRelationship.TYPE_SKILL.equals(e.getRelationType()))
                .sorted(Comparator.comparingInt(CompanyRelationship::getWeight).reversed())
                .toList();
        if (tech.isEmpty()) return Optional.empty();
        String top = tech.stream().limit(8).map(CompanyRelationship::getTarget)
                .collect(Collectors.joining(", "));
        return Optional.of(new CompanyInsight("TECHNOLOGY",
                tech.size() + " technologies/skills observed",
                "Most demanded (by observation count): " + top));
    }
}
