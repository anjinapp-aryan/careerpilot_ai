package ai.careerpilot.learning.career.goal;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 7.19.2 — a deterministic, curated lookup from skill name to a certification/project/
 * practice suggestion. NOT AI-generated: a fixed table, same pattern as {@code TechnologyTaxonomy}.
 * Unmapped skills get an honest generic fallback rather than an invented specific suggestion.
 */
final class SkillSuggestionTable {

    record Suggestion(String certification, String project, String practice) {}

    private static final Map<String, Suggestion> TABLE = buildTable();
    private static final Suggestion FALLBACK = new Suggestion(
            "No curated certification on file for this skill",
            "Build a small project that exercises this skill end-to-end",
            "Practice through hands-on use — no curated resource on file yet");

    private SkillSuggestionTable() {}

    static Suggestion suggestionsFor(String skill) {
        if (skill == null) return FALLBACK;
        return TABLE.getOrDefault(skill.toLowerCase(Locale.ROOT).trim(), FALLBACK);
    }

    private static Map<String, Suggestion> buildTable() {
        Map<String, Suggestion> m = new LinkedHashMap<>();
        m.put("java", new Suggestion("Oracle Certified Professional Java", "Build a Spring Boot REST API with tests", "Solve JVM performance/GC tuning exercises"));
        m.put("kubernetes", new Suggestion("Certified Kubernetes Administrator (CKA)", "Deploy a multi-service app on a local k8s cluster", "Practice writing Helm charts and debugging pod failures"));
        m.put("aws", new Suggestion("AWS Certified Solutions Architect", "Deploy a serverless app on Lambda + API Gateway", "Practice IAM policy and cost-optimization scenarios"));
        m.put("azure", new Suggestion("Microsoft Certified: Azure Solutions Architect", "Deploy an app on Azure App Service + Functions", "Practice Azure DevOps pipeline setup"));
        m.put("gcp", new Suggestion("Google Cloud Professional Cloud Architect", "Deploy an app on Cloud Run + Firestore", "Practice GCP IAM and networking scenarios"));
        m.put("docker", new Suggestion("Docker Certified Associate", "Containerize an existing app with a multi-stage Dockerfile", "Practice image size optimization and layer caching"));
        m.put("kafka", new Suggestion("Confluent Certified Developer for Apache Kafka", "Build a producer/consumer pipeline with schema registry", "Practice consumer-group rebalancing scenarios"));
        m.put("system design", new Suggestion("No curated certification for system design", "Design a URL shortener / rate limiter from scratch on paper", "Practice mock system-design interviews"));
        m.put("react", new Suggestion("Meta Front-End Developer Certificate", "Build a full CRUD app with React + a real API", "Practice component composition and state management patterns"));
        m.put("python", new Suggestion("PCEP/PCAP Python certifications", "Build a data pipeline or automation script", "Practice algorithmic problems in Python"));
        m.put("terraform", new Suggestion("HashiCorp Certified: Terraform Associate", "Provision a small cloud environment via Terraform modules", "Practice writing reusable Terraform modules"));
        m.put("machine learning", new Suggestion("Coursera/DeepLearning.AI ML Specialization", "Train and deploy a small model end-to-end", "Practice on a public dataset (e.g. Kaggle)"));
        m.put("sql", new Suggestion("No curated certification for SQL", "Write complex queries against a real dataset", "Practice query optimization and indexing scenarios"));
        m.put("microservices", new Suggestion("No curated certification for microservices", "Split a monolith into 2-3 communicating services", "Practice designing service boundaries and API contracts"));
        return m;
    }
}
