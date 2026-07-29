package ai.careerpilot.domain;

import ai.careerpilot.mission.MissionStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Mission Engine, Phase 1 — a user's long-term career objective, e.g. "Become Principal Java AI
 * Architect in Europe within 24 months". <b>Mission is stable</b>: this row rarely changes once
 * set — the day-to-day tactics for pursuing it (which jobs to target this week, which skill to
 * study next) belong to other, more volatile parts of the platform, not here. Countries and
 * industries are plain JSONB string lists ({@code targetIndustriesJson}/{@code
 * targetCountriesJson}), never enums or a hardcoded list — "countries are data, not code."
 * Consumed today only by this CRUD API; future AI agents (resume tailoring, job matching,
 * interview prep) are expected to read this row as their source of long-term intent, but no such
 * consumer exists yet in Phase 1.
 */
@Entity
@Table(name = "career_mission")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CareerMission {
    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;

    @Column(name = "mission_statement", nullable = false, columnDefinition = "text")
    private String missionStatement;

    @Column(name = "target_role", nullable = false) private String targetRole;
    @Column(name = "target_level") private String targetLevel;

    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "target_industries", columnDefinition = "jsonb")
    private String targetIndustriesJson;

    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "target_countries", columnDefinition = "jsonb")
    private String targetCountriesJson;

    @Column(name = "salary_expectation_min") private BigDecimal salaryExpectationMin;
    @Column(name = "salary_expectation_max") private BigDecimal salaryExpectationMax;
    @Column(name = "salary_currency") private String salaryCurrency;

    @Column(name = "timeline_months") private Integer timelineMonths;
    @Column(name = "career_direction") private String careerDirection;

    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "skills_to_acquire", columnDefinition = "jsonb")
    private String skillsToAcquireJson;

    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "current_skills", columnDefinition = "jsonb")
    private String currentSkillsJson;

    @Column(name = "career_ambition", columnDefinition = "text") private String careerAmbition;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private MissionStatus status = MissionStatus.ACTIVE;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
