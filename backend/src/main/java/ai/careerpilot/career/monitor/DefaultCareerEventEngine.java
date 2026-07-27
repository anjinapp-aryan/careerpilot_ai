package ai.careerpilot.career.monitor;

import ai.careerpilot.domain.CareerStrategy;
import ai.careerpilot.domain.Interview;
import ai.careerpilot.domain.Resume;
import ai.careerpilot.offer.Offer;
import ai.careerpilot.repo.CareerStrategyRepository;
import ai.careerpilot.repo.InterviewRepository;
import ai.careerpilot.repo.OfferRepository;
import ai.careerpilot.repo.ResumeRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 11.5 — the default {@link CareerEventEngine}. Each check is a thin read-only wrapper
 * around an existing repository/entity — no new query, no new table, no fabricated data. A
 * signal that isn't present (no resume, no career strategy row, no offer, no upcoming
 * interview) simply produces no alert for that category, never a guessed one.
 */
public class DefaultCareerEventEngine implements CareerEventEngine {

    private final ResumeRepository resumes;
    private final CareerStrategyRepository careerStrategies;
    private final OfferRepository offers;
    private final InterviewRepository interviews;
    private final Duration resumeStaleness;
    private final Duration interviewReminderWindow;

    public DefaultCareerEventEngine(ResumeRepository resumes, CareerStrategyRepository careerStrategies,
                                     OfferRepository offers, InterviewRepository interviews,
                                     Duration resumeStaleness, Duration interviewReminderWindow) {
        this.resumes = resumes;
        this.careerStrategies = careerStrategies;
        this.offers = offers;
        this.interviews = interviews;
        this.resumeStaleness = resumeStaleness;
        this.interviewReminderWindow = interviewReminderWindow;
    }

    @Override
    public List<CareerAlert> detectEvents(UUID userId) {
        List<CareerAlert> alerts = new ArrayList<>();
        checkResumeOutdated(userId).ifPresent(alerts::add);
        checkCareerStrategySignals(userId, alerts);
        checkSalaryBelowMarket(userId).ifPresent(alerts::add);
        checkUpcomingInterviews(userId, alerts);
        return alerts;
    }

    private Optional<CareerAlert> checkResumeOutdated(UUID userId) {
        List<Resume> list = resumes.findByUserIdOrderByCreatedAtDesc(userId);
        if (list.isEmpty()) {
            return Optional.empty();
        }
        Resume latest = list.get(0);
        if (latest.getCreatedAt() == null || latest.getCreatedAt().isAfter(Instant.now().minus(resumeStaleness))) {
            return Optional.empty();
        }
        long ageDays = ChronoUnit.DAYS.between(latest.getCreatedAt(), Instant.now());
        return Optional.of(CareerAlert.of(userId, CareerAlertType.RESUME_OUTDATED, CareerAlertSeverity.MEDIUM,
                "Your resume hasn't been updated in " + ageDays + " days",
                evidence("resumeId", latest.getId(), "ageDays", ageDays)));
    }

    private void checkCareerStrategySignals(UUID userId, List<CareerAlert> alerts) {
        Optional<CareerStrategy> strategy = careerStrategies.findByUserId(userId);
        if (strategy.isEmpty()) {
            return;
        }
        CareerStrategy s = strategy.get();

        String skillGaps = s.getSkillGapsJson();
        if (skillGaps != null && !skillGaps.isBlank()) {
            alerts.add(CareerAlert.of(userId, CareerAlertType.LEARNING_SUGGESTION, CareerAlertSeverity.LOW,
                    "New skill gaps identified worth addressing", Map.of("skillGaps", skillGaps)));
            if (skillGaps.toLowerCase().contains("certif")) {
                alerts.add(CareerAlert.of(userId, CareerAlertType.MISSING_CERTIFICATION, CareerAlertSeverity.MEDIUM,
                        "A relevant certification gap was identified", Map.of("skillGaps", skillGaps)));
            }
        }

        String promotionReadiness = s.getPromotionReadinessJson();
        if (promotionReadiness != null && !promotionReadiness.isBlank()) {
            alerts.add(CareerAlert.of(userId, CareerAlertType.PROMOTION_READY, CareerAlertSeverity.INFO,
                    "Promotion readiness has been assessed", Map.of("promotionReadiness", promotionReadiness)));
        }
    }

    private Optional<CareerAlert> checkSalaryBelowMarket(UUID userId) {
        List<Offer> list = offers.findByUserIdOrderByCreatedAtDesc(userId);
        if (list.isEmpty()) {
            return Optional.empty();
        }
        Offer latest = list.get(0);
        if (latest.getBaseSalary() == null || latest.getMarketP50() == null) {
            return Optional.empty();
        }
        if (latest.getBaseSalary().compareTo(latest.getMarketP50()) >= 0) {
            return Optional.empty();
        }
        return Optional.of(CareerAlert.of(userId, CareerAlertType.SALARY_BELOW_MARKET, CareerAlertSeverity.HIGH,
                "Your offer's base salary is below the market median",
                Map.of("baseSalary", latest.getBaseSalary(), "marketP50", latest.getMarketP50())));
    }

    private void checkUpcomingInterviews(UUID userId, List<CareerAlert> alerts) {
        Instant now = Instant.now();
        Instant windowEnd = now.plus(interviewReminderWindow);
        for (Interview interview : interviews.findByUserIdOrderByCreatedAtDesc(userId)) {
            Instant scheduledAt = interview.getScheduledAt();
            if (scheduledAt != null && scheduledAt.isAfter(now) && scheduledAt.isBefore(windowEnd)) {
                alerts.add(CareerAlert.of(userId, CareerAlertType.INTERVIEW_REMINDER, CareerAlertSeverity.HIGH,
                        "Upcoming " + interview.getInterviewType() + " interview",
                        evidence("interviewId", interview.getId(), "scheduledAt", scheduledAt)));
            }
        }
    }

    /**
     * {@code Map.of} throws on a null value — entity ids can be {@code null} for a not-yet-flushed
     * row (and are {@code null} in every unit test that doesn't hit a real database), so evidence
     * maps use this instead: a plain, null-tolerant map.
     */
    private static Map<String, Object> evidence(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }
}
