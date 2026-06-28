package ai.careerpilot.jobdiscovery;

import ai.careerpilot.domain.Job;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, keyword-based classification of a normalized {@link Job} — the cheap
 * enrichment tier of the recommendation engine. Runs once at ingest, never calls an LLM.
 * Fills {@code remoteType}, {@code sponsorshipAvailable}, {@code relocationSupport},
 * {@code companySize}, {@code requiredExperience} so the rule-based scorer and the UI
 * facets have structured signals to work with. Pure functions, no I/O.
 *
 * <p>Idempotent: re-running on an already-enriched job yields the same values, so the
 * upsert path in {@code JobAggregationService} can call it on every refresh.
 */
@Component
public class JobEnricher {

    private static final Pattern YEARS = Pattern.compile(
            "(\\d{1,2})\\s*\\+?\\s*(?:years?|yrs?)(?:\\s+(?:of\\s+)?experience)?", Pattern.CASE_INSENSITIVE);

    private final JobTaxonomy taxonomy;

    public JobEnricher(JobTaxonomy taxonomy) {
        this.taxonomy = taxonomy;
    }

    /** Mutates {@code job} in place with derived enrichment fields. */
    public void enrich(Job job) {
        String haystack = (safe(job.getTitle()) + " " + safe(job.getDescription()) + " "
                + safe(job.getLocation())).toLowerCase();

        job.setRemoteType(deriveRemoteType(haystack, job.getRemote()));
        job.setSponsorshipAvailable(detectSponsorship(haystack));
        job.setRelocationSupport(detectRelocation(haystack));
        job.setCompanySize(deriveCompanySize(haystack));
        job.setRequiredExperience(parseRequiredExperience(safe(job.getDescription()) + " " + safe(job.getTitle())));
        // Industry/job-family classification drives the recommendation quality filter.
        job.setJobFamily(taxonomy.classifyFamily(job.getTitle(), job.getDescription()));
    }

    String deriveRemoteType(String haystack, Boolean remoteFlag) {
        if (haystack.contains("hybrid")) return "HYBRID";
        if (Boolean.TRUE.equals(remoteFlag) || haystack.contains("remote") || haystack.contains("work from home")
                || haystack.contains("wfh")) {
            return "REMOTE";
        }
        if (haystack.contains("on-site") || haystack.contains("onsite") || haystack.contains("in office")
                || haystack.contains("in-office")) {
            return "ONSITE";
        }
        return remoteFlag == null ? null : (remoteFlag ? "REMOTE" : "ONSITE");
    }

    Boolean detectSponsorship(String haystack) {
        boolean yes = haystack.contains("visa sponsorship") || haystack.contains("sponsor visa")
                || haystack.contains("sponsorship available") || haystack.contains("h1b")
                || haystack.contains("h-1b") || haystack.contains("visa support")
                || haystack.contains("work permit") || haystack.contains("we sponsor");
        boolean no = haystack.contains("no visa sponsorship") || haystack.contains("cannot sponsor")
                || haystack.contains("not able to sponsor") || haystack.contains("no sponsorship");
        if (no) return Boolean.FALSE;
        return yes ? Boolean.TRUE : null; // unknown stays null (no false signal)
    }

    Boolean detectRelocation(String haystack) {
        boolean yes = haystack.contains("relocation assistance") || haystack.contains("relocation package")
                || haystack.contains("relocation support") || haystack.contains("we relocate")
                || haystack.contains("relocation provided") || haystack.contains("willing to relocate you");
        return yes ? Boolean.TRUE : null;
    }

    String deriveCompanySize(String haystack) {
        if (haystack.contains("fortune 500") || haystack.contains("enterprise") || haystack.contains("multinational")
                || haystack.contains("10,000+") || haystack.contains("global leader")) {
            return "ENTERPRISE";
        }
        if (haystack.contains("scale-up") || haystack.contains("scaleup") || haystack.contains("series c")
                || haystack.contains("series d")) {
            return "MID";
        }
        if (haystack.contains("startup") || haystack.contains("start-up") || haystack.contains("early stage")
                || haystack.contains("seed stage") || haystack.contains("series a") || haystack.contains("series b")) {
            return "STARTUP";
        }
        return null; // these free feeds rarely state size; leave unknown
    }

    Integer parseRequiredExperience(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher m = YEARS.matcher(text);
        Integer min = null;
        while (m.find()) {
            try {
                int n = Integer.parseInt(m.group(1));
                if (n >= 0 && n <= 40 && (min == null || n < min)) min = n; // take the lowest stated bar
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return min;
    }

    private static String safe(String v) {
        return v == null ? "" : v;
    }
}
