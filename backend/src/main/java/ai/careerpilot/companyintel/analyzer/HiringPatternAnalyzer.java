package ai.careerpilot.companyintel.analyzer;

import ai.careerpilot.domain.CompanyRelationship;
import ai.careerpilot.domain.CompanyTimelineEvent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Phase 7.13 — derives the company's hiring pattern from observed roles and timeline outcomes. */
@Component
public class HiringPatternAnalyzer {

    public Optional<CompanyInsight> analyze(List<CompanyRelationship> edges, List<CompanyTimelineEvent> events) {
        List<String> roles = edges.stream()
                .filter(e -> CompanyRelationship.TYPE_ROLE.equals(e.getRelationType()))
                .map(CompanyRelationship::getTarget)
                .limit(6)
                .toList();
        long applications = count(events, "APPLICATION_SUBMITTED");
        long interviews = count(events, "INTERVIEW_SCHEDULED") + count(events, "INTERVIEW_COMPLETED");
        long offers = count(events, "OFFER_RECEIVED");
        if (roles.isEmpty() && applications == 0) return Optional.empty();
        String headline = roles.isEmpty() ? "Outcome history only"
                : "Hires for: " + String.join(", ", roles);
        return Optional.of(new CompanyInsight("HIRING_PATTERN", headline,
                applications + " applications, " + interviews + " interviews, " + offers + " offers observed"));
    }

    private static long count(List<CompanyTimelineEvent> events, String type) {
        return events.stream().filter(e -> type.equals(e.getEventType())).count();
    }

    public static String distribution(List<CompanyTimelineEvent> events) {
        return events.stream().collect(Collectors.groupingBy(CompanyTimelineEvent::getEventType,
                        Collectors.counting()))
                .entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
    }
}
