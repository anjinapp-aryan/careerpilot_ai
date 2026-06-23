package ai.careerpilot.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Canned target-role specifications for the Resume Optimization modes. The agent's
 * ATS stage always optimizes a resume against a target job description; for the
 * preset modes (1–6) we synthesize a representative JD so the user does not need to
 * paste one. Modes "upload_jd" and "select_job" supply their own JD and do not use
 * this catalog.
 */
public final class OptimizationModeCatalog {

    private OptimizationModeCatalog() {}

    public record ModeSpec(String title, String description) {}

    private static final ModeSpec GENERIC = new ModeSpec(
            "ATS-Optimized Professional",
            "A role-agnostic Applicant Tracking System optimization target. Emphasize "
            + "quantified achievements, clear section headers, standard job titles, strong "
            + "action verbs, and broad industry-standard keywords for the candidate's field. "
            + "Avoid graphics, tables, and non-standard formatting that ATS parsers fail on.");

    private static final Map<String, ModeSpec> CATALOG = new LinkedHashMap<>();
    static {
        CATALOG.put("generic_ats", GENERIC);
        CATALOG.put("senior_java_developer", new ModeSpec(
                "Senior Java Developer",
                "Senior Java engineer building production backend services. Required: Java 17+, "
                + "Spring Boot, REST APIs, microservices, SQL/PostgreSQL, JPA/Hibernate, JUnit, "
                + "CI/CD, Git, Docker, Kafka or RabbitMQ, cloud (AWS/GCP/Azure). Strong on "
                + "concurrency, performance tuning, clean code, and code review."));
        CATALOG.put("java_architect", new ModeSpec(
                "Java Architect",
                "Hands-on Java architect owning system design for large-scale distributed systems. "
                + "Required: microservices architecture, domain-driven design, event-driven "
                + "architecture (Kafka), API design, Spring Cloud, Kubernetes, observability, "
                + "security, scalability, and technical leadership across teams."));
        CATALOG.put("solution_architect", new ModeSpec(
                "Solution Architect",
                "Solution architect translating business requirements into end-to-end technical "
                + "solutions. Required: solution design, cloud architecture (AWS/Azure/GCP), "
                + "integration patterns, API gateways, cost optimization, non-functional "
                + "requirements, stakeholder communication, and architecture decision records."));
        CATALOG.put("enterprise_architect", new ModeSpec(
                "Enterprise Architect",
                "Enterprise architect aligning technology strategy with business goals across the "
                + "organization. Required: enterprise architecture frameworks (TOGAF), capability "
                + "modeling, technology roadmaps, governance, cloud transformation, vendor "
                + "strategy, security/compliance, and executive stakeholder leadership."));
        CATALOG.put("engineering_manager", new ModeSpec(
                "Engineering Manager",
                "Engineering manager leading software teams and delivery. Required: people "
                + "management, mentoring, hiring, agile/scrum delivery, roadmap ownership, "
                + "cross-functional collaboration, stakeholder management, and a strong "
                + "technical background in backend/cloud systems."));
    }

    /** Lookup by mode code; falls back to the generic ATS target for unknown/blank modes. */
    public static ModeSpec specFor(String mode) {
        if (mode == null) return GENERIC;
        return CATALOG.getOrDefault(mode.trim().toLowerCase(), GENERIC);
    }
}
