package ai.careerpilot.companyintel.analyzer;

import ai.careerpilot.domain.CompanyRelationship;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Phase 7.13 — the skills this company keeps asking for, ranked by observation weight. */
@Component
public class SkillDemandAnalyzer {

    public Optional<CompanyInsight> analyze(List<CompanyRelationship> edges) {
        List<CompanyRelationship> skills = edges.stream()
                .filter(e -> CompanyRelationship.TYPE_SKILL.equals(e.getRelationType()))
                .sorted(Comparator.comparingInt(CompanyRelationship::getWeight).reversed())
                .toList();
        if (skills.isEmpty()) return Optional.empty();
        String ranked = skills.stream().limit(10)
                .map(e -> e.getTarget() + " (x" + e.getWeight() + ")")
                .collect(Collectors.joining(", "));
        return Optional.of(new CompanyInsight("SKILL_DEMAND",
                skills.size() + " distinct skills demanded", "By observation count: " + ranked));
    }
}
