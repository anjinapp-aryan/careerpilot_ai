package ai.careerpilot.review;

import java.util.List;

/**
 * Phase 7.12 — the output of a single reviewer. Reviewers are pure and stateless: a section is a
 * read-only assessment ({@code score} 0-100, human-readable {@code reasons} and {@code suggestions})
 * and never carries a mutation back to any artifact.
 */
public record ReviewSection(String reviewer, Integer score, List<String> reasons, List<String> suggestions) {

    public static ReviewSection of(String reviewer, int score, List<String> reasons, List<String> suggestions) {
        return new ReviewSection(reviewer, score, reasons == null ? List.of() : reasons,
                suggestions == null ? List.of() : suggestions);
    }
}
