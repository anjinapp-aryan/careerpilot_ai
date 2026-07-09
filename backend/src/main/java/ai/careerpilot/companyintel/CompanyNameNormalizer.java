package ai.careerpilot.companyintel;

import java.util.Locale;

/**
 * Phase 7.13 — canonical company-name key so "Google LLC", "google" and " GOOGLE, Inc. " share one
 * knowledge row. Deterministic: lowercase, strip punctuation and common legal suffixes, collapse
 * whitespace.
 */
public final class CompanyNameNormalizer {

    private CompanyNameNormalizer() {}

    public static String normalize(String name) {
        if (name == null) return "";
        String n = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\b(incorporated|corporation|technologies|technology|solutions|systems"
                        + "|inc|llc|ltd|gmbh|corp|co|plc|pvt|private|limited|labs|sa|ag|bv)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
        // Never normalize away the whole name (e.g. a company literally named "Labs").
        return n.isEmpty() ? name.toLowerCase(Locale.ROOT).trim() : n;
    }
}
