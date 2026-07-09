package ai.careerpilot.companyintel.analyzer;

import ai.careerpilot.companyintel.KnowledgeSection;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/** Phase 7.13 — surfaces observed culture/reputation knowledge; empty when nothing is on file. */
@Component
public class CultureAnalyzer {

    public Optional<CompanyInsight> analyze(Map<String, String> sections) {
        String culture = sections.get(KnowledgeSection.CULTURE.key());
        String reputation = sections.get(KnowledgeSection.REPUTATION.key());
        if (isBlank(culture) && isBlank(reputation)) return Optional.empty();
        String detail = !isBlank(culture) ? culture : reputation;
        return Optional.of(new CompanyInsight("CULTURE",
                !isBlank(culture) ? "Culture knowledge on file" : "Reputation knowledge on file",
                truncate(detail)));
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static String truncate(String s) {
        return s.length() <= 400 ? s : s.substring(0, 400) + "…";
    }
}
