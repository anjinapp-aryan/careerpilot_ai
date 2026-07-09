package ai.careerpilot.companyintel.analyzer;

import ai.careerpilot.domain.CompanyTimelineEvent;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Phase 7.13 — activity trend over the timeline: is knowledge about this company still growing? */
@Component
public class CompanyTrendAnalyzer {

    public Optional<CompanyInsight> analyze(List<CompanyTimelineEvent> events) {
        if (events.isEmpty()) return Optional.empty();
        Instant now = Instant.now();
        long last30 = events.stream().filter(e -> within(e, now, 30)).count();
        long prior30 = events.stream().filter(e -> within(e, now, 60) && !within(e, now, 30)).count();
        String trend = last30 > prior30 ? "GROWING" : last30 < prior30 ? "COOLING" : "STEADY";
        return Optional.of(new CompanyInsight("TREND", trend,
                last30 + " events in the last 30 days vs " + prior30 + " in the 30 days before"));
    }

    private static boolean within(CompanyTimelineEvent e, Instant now, int days) {
        return e.getOccurredAt() != null
                && Duration.between(e.getOccurredAt(), now).toDays() < days;
    }
}
