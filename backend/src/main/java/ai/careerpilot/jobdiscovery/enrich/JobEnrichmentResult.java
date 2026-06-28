package ai.careerpilot.jobdiscovery.enrich;

import java.math.BigDecimal;
import java.util.List;

/**
 * Validated output of one LLM job-enrichment call — the semantic signals the cheap keyword tier
 * cannot derive. Immutable; produced by {@link JobAiEnrichmentExtractor} only after parse + validation,
 * so persistence never sees a half-formed extraction.
 */
public record JobEnrichmentResult(
        String seniorityLevel,
        List<String> normalizedSkills,
        List<String> domains,
        String employmentType,
        BigDecimal salaryBandMin,
        BigDecimal salaryBandMax,
        String salaryCurrency,
        Boolean salaryEstimated,
        String summary,
        BigDecimal confidenceScore) {
}
