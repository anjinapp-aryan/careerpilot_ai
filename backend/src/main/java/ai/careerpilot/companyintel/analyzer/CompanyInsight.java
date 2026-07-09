package ai.careerpilot.companyintel.analyzer;

/**
 * Phase 7.13 — one deterministic, explainable insight produced by a company analyzer. Analyzers
 * read only observed knowledge/graph/timeline facts; none calls an LLM or fabricates data.
 */
public record CompanyInsight(String kind, String headline, String detail) {}
