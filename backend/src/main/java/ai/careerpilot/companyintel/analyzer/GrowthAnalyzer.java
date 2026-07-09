package ai.careerpilot.companyintel.analyzer;

import ai.careerpilot.companyintel.KnowledgeSection;
import ai.careerpilot.domain.CompanyRelationship;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Phase 7.13 — growth read from observed role breadth + any growth-signal knowledge on file. */
@Component
public class GrowthAnalyzer {

    public Optional<CompanyInsight> analyze(Map<String, String> sections, List<CompanyRelationship> edges) {
        long roles = edges.stream()
                .filter(e -> CompanyRelationship.TYPE_ROLE.equals(e.getRelationType()))
                .count();
        String signals = sections.get(KnowledgeSection.GROWTH_SIGNALS.key());
        if (roles == 0 && (signals == null || signals.isBlank())) return Optional.empty();
        String headline = roles >= 3 ? "Actively hiring across " + roles + " observed roles"
                : roles + " role(s) observed";
        return Optional.of(new CompanyInsight("GROWTH", headline,
                signals == null || signals.isBlank() ? "Role breadth is the only growth signal observed so far"
                        : signals.length() > 400 ? signals.substring(0, 400) + "…" : signals));
    }
}
