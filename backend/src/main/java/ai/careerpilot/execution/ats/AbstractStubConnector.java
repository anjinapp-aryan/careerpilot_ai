package ai.careerpilot.execution.ats;

import ai.careerpilot.domain.Job;

import java.util.Locale;
import java.util.Map;

/**
 * Phase 2E.3 — shared base for the inert ATS connector stubs. Holds the URL-substring
 * {@link #detect(Job)} routing (the one real, side-effect-free behaviour) and makes every
 * side-effecting method throw {@link UnsupportedOperationException}. Subclasses supply only their
 * {@link #name()} and the host token they recognise. {@link #isConfigured()} is always false in the
 * 2E build, so the registry never routes real work here.
 */
public abstract class AbstractStubConnector implements ATSConnector {

    private static final String STUB = "ATS connector not wired — Phase 2E inert stub";

    /** Lowercase host token that identifies this ATS in a job URL/source (e.g. "greenhouse.io"). */
    protected abstract String hostToken();

    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public boolean detect(Job job) {
        if (job == null) return false;
        String haystack = String.join(" ",
                nz(job.getSourceUrl()), nz(job.getExternalUrl()), nz(job.getSource()))
                .toLowerCase(Locale.ROOT);
        return haystack.contains(hostToken());
    }

    @Override public void authenticate(Map<String, String> credentials) { throw stub(); }
    @Override public Map<String, String> extractForm(Job job) { throw stub(); }
    @Override public String submit(Job job, Map<String, String> answers) { throw stub(); }
    @Override public String track(String confirmationReference) { throw stub(); }

    private static UnsupportedOperationException stub() {
        return new UnsupportedOperationException(STUB);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
