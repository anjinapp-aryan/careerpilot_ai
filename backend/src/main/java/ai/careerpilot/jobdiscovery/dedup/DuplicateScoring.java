package ai.careerpilot.jobdiscovery.dedup;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure-function text confirmation signals for fuzzy duplicate detection (Phase 2 Increment C).
 * Embedding cosine similarity (from Increment A) is the primary candidate-generation signal —
 * these functions exist to <em>confirm</em> a candidate isn't a false positive (two different
 * roles at the same company can be semantically similar without being the same posting).
 *
 * <p>No I/O, no AI calls — deterministic and unit-testable like {@code JobEnricher}/{@code JobScoring}.
 */
@Component
public class DuplicateScoring {

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9 ]");
    /** Common legal-entity suffixes that make the same company read as "different" textually. */
    private static final Pattern COMPANY_SUFFIX = Pattern.compile(
            "\\b(inc|incorporated|llc|ltd|limited|gmbh|corp|corporation|co|company|plc|llp|sa|ag|kg|bv)\\b");

    /** Lowercase, strip punctuation, collapse whitespace — the shared normalization for both fields. */
    String normalize(String s) {
        if (s == null) return "";
        String lower = s.toLowerCase().trim();
        String stripped = NON_ALNUM.matcher(lower).replaceAll(" ");
        return stripped.replaceAll("\\s+", " ").trim();
    }

    /** Company-specific normalization: also drops legal-entity suffixes ("Acme Inc." → "acme"). */
    String normalizeCompany(String company) {
        String normalized = normalize(company);
        return COMPANY_SUFFIX.matcher(normalized).replaceAll("").replaceAll("\\s+", " ").trim();
    }

    /** True when two company names are the same after normalization (handles "Acme" vs "Acme Inc."). */
    public boolean companyMatches(String a, String b) {
        String na = normalizeCompany(a);
        String nb = normalizeCompany(b);
        return !na.isEmpty() && na.equals(nb);
    }

    /** Jaccard similarity (0.0–1.0) over the normalized token sets of two job titles. */
    public double titleJaccard(String a, String b) {
        Set<String> ta = tokens(a);
        Set<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(ta);
        intersection.retainAll(tb);
        Set<String> union = new HashSet<>(ta);
        union.addAll(tb);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private Set<String> tokens(String s) {
        String normalized = normalize(s);
        if (normalized.isEmpty()) return Set.of();
        return new HashSet<>(Arrays.asList(normalized.split(" ")));
    }
}
