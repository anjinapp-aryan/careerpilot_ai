package ai.careerpilot.execution.browser.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Phase 12C.5 — decides whether the harness may navigate to a caller-supplied URL.
 *
 * <p><b>This class exists because the harness is a server-side request forgery primitive.</b> It
 * takes a URL from a caller and makes the server fetch it with a full browser — cookies, JavaScript
 * execution, and access to whatever the container can reach on the internal Docker network
 * ({@code redis}, {@code minio}) and to cloud metadata endpoints. Without a policy,
 * "validate this careers page" is also "read my instance metadata and screenshot it for me".
 *
 * <p>Three independent checks, deliberately layered so defeating one is not enough:
 * <ol>
 *   <li><b>Scheme</b> — {@code https} only by default. {@code file:}, {@code data:} and friends
 *       are not employer websites.</li>
 *   <li><b>Host allow-list</b> — the known public ATS hosts, from {@link AtsPlatform}, plus
 *       anything an operator explicitly adds. A company's own careers portal is a legitimate target
 *       and must be added deliberately, one host at a time.</li>
 *   <li><b>Resolved address</b> — the allow-list is checked against the literal host, but DNS is
 *       attacker-influenced, so the resolved IP is independently rejected if it is loopback,
 *       link-local (which is where cloud metadata lives), site-local, or otherwise non-public.</li>
 * </ol>
 *
 * <p>The address check runs even for allow-listed hosts. An allow-listed name that resolves into
 * the private range is precisely the DNS-rebinding case, and trusting the name there would make the
 * allow-list the vulnerability rather than the control.
 */
@Component
public class ValidationUrlPolicy {

    private static final Logger log = LoggerFactory.getLogger(ValidationUrlPolicy.class);

    /** Where cloud instance metadata lives on AWS/GCP/Azure/Oracle. Never a legitimate target. */
    private static final String METADATA_ADDRESS = "169.254.169.254";

    private final boolean allowListEnforced;
    private final Set<String> extraAllowedHosts;
    private final boolean requireHttps;

    public ValidationUrlPolicy(
            @Value("${browser.validation.allow-list-enforced:true}") boolean allowListEnforced,
            @Value("${browser.validation.allowed-hosts:}") String extraAllowedHostsCsv,
            @Value("${browser.validation.require-https:true}") boolean requireHttps) {
        this.allowListEnforced = allowListEnforced;
        this.requireHttps = requireHttps;
        this.extraAllowedHosts = parseHosts(extraAllowedHostsCsv);
        // Names logged (not exposed on the unauthenticated diagnostics endpoint) so an operator can
        // confirm which custom-domain entries actually loaded and which were rejected as unsafe.
        log.info("BROWSER_VALIDATION url policy: allowListEnforced={} requireHttps={} extraHosts={} {}",
                allowListEnforced, requireHttps, this.extraAllowedHosts.size(),
                this.extraAllowedHosts.stream().sorted().toList());
    }

    /** The verdict. {@code allowed=false} always carries a reason, for the report and the log. */
    public record Verdict(boolean allowed, String reason, AtsPlatform platform) {
        static Verdict deny(String reason) {
            return new Verdict(false, reason, AtsPlatform.UNKNOWN);
        }
    }

    public Verdict evaluate(String url) {
        if (url == null || url.isBlank()) return Verdict.deny("no URL supplied");

        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (RuntimeException e) {
            return Verdict.deny("URL is not parseable");
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (requireHttps) {
            if (!"https".equals(scheme)) {
                return Verdict.deny("only https URLs may be validated (got scheme '" + scheme + "')");
            }
        } else if (!"https".equals(scheme) && !"http".equals(scheme)) {
            return Verdict.deny("only http/https URLs may be validated (got scheme '" + scheme + "')");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) return Verdict.deny("URL has no host");
        host = host.toLowerCase(Locale.ROOT);

        // Name check before address check. Both are always enforced — order changes only which
        // reason is reported and whether a DNS lookup happens at all. Rejecting an unknown host
        // without resolving it means an attacker-supplied hostname never even triggers a lookup
        // from this server, and it keeps the cheap check in front of the network round-trip.
        AtsPlatform platform = AtsPlatform.detect(url);
        if (allowListEnforced && platform == AtsPlatform.UNKNOWN && !isExplicitlyAllowed(host)) {
            return Verdict.deny("host '" + host + "' is not a known ATS and is not in "
                    + "browser.validation.allowed-hosts");
        }

        // Runs for permitted hosts too. DNS is attacker-influenced, so an allow-listed name that
        // resolves into a private range is the rebinding case — trusting the name here would make
        // the allow-list the vulnerability rather than the control.
        String addressDenial = denyIfNotPublic(host);
        if (addressDenial != null) return Verdict.deny(addressDenial);

        return new Verdict(true, allowListEnforced ? "host permitted" : "allow-list not enforced", platform);
    }

    private boolean isExplicitlyAllowed(String host) {
        for (String allowed : extraAllowedHosts) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) return true;
        }
        return false;
    }

    /**
     * Rejects any host resolving to a non-public address. Returns the denial reason, or {@code null}
     * when the address is acceptable.
     *
     * <p>A DNS failure is a denial, not a pass. "We could not determine where this points" is not a
     * reason to open a browser at it.
     */
    private String denyIfNotPublic(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception e) {
            return "host '" + host + "' could not be resolved";
        }
        if (addresses.length == 0) return "host '" + host + "' resolved to no address";

        for (InetAddress address : addresses) {
            if (METADATA_ADDRESS.equals(address.getHostAddress())) {
                return "host resolves to the cloud metadata endpoint";
            }
            if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                return "host resolves to a non-public address (" + address.getHostAddress() + ")";
            }
        }
        return null;
    }

    /**
     * Entries too broad to be a company's ATS front-end. Adding one of these would silently turn the
     * allow-list into "allow everything", because {@link #isExplicitlyAllowed} matches subdomains.
     */
    private static final Set<String> FORBIDDEN_BROAD_SUFFIXES = Set.of(
            "com", "net", "org", "io", "co", "dev", "app", "ai", "jobs", "careers", "cloud",
            "co.uk", "com.au", "co.in", "com.br", "co.jp", "de", "fr", "nl", "eu", "us");

    /**
     * P1 — parse the operator-supplied custom-domain list, refusing entries that would defeat the
     * control they are being added to.
     *
     * <p>Custom ATS front-ends are the reason this list exists: measured in the F5 audit, 7 of 16
     * sampled Greenhouse employers serve their board from their own domain
     * ({@code careers.airbnb.com}, {@code jobs.dropbox.com}, {@code stripe.com}, …), so 24% of real
     * postings were refused. The fix is to name those hosts here — deliberately, one at a time.
     *
     * <p>The guard exists because matching is suffix-based: an entry of {@code com} would admit
     * every host on the internet including internal ones behind a public CNAME, converting an SSRF
     * control into an SSRF vector. A bad entry is dropped with a warning rather than failing
     * startup, matching how {@code BrowserRolloutGate} handles a malformed allow-list uuid — an
     * operator typo must not take the service down, but it must not silently widen access either.
     */
    private static Set<String> parseHosts(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .filter(ValidationUrlPolicy::isSafeAllowListEntry)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean isSafeAllowListEntry(String host) {
        if (host.contains("/") || host.contains(":") || host.contains("*")) {
            log.warn("BROWSER_VALIDATION ignoring allowed-host '{}': entries are bare hostnames, "
                    + "not URLs, ports or wildcards", host);
            return false;
        }
        if (!host.contains(".")) {
            log.warn("BROWSER_VALIDATION ignoring allowed-host '{}': single-label entry would match "
                    + "every subdomain of a bare TLD", host);
            return false;
        }
        if (FORBIDDEN_BROAD_SUFFIXES.contains(host)) {
            log.warn("BROWSER_VALIDATION ignoring allowed-host '{}': too broad — suffix matching "
                    + "would admit every host under it, defeating the allow-list", host);
            return false;
        }
        return true;
    }

    /** Diagnostics. Lists the policy, not any URL a caller has tried. */
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("allowListEnforced", allowListEnforced);
        out.put("requireHttps", requireHttps);
        out.put("knownAtsHosts", List.of(AtsPlatform.values()).stream()
                .filter(p -> p != AtsPlatform.UNKNOWN)
                .flatMap(p -> p.hostTokens().stream())
                .toList());
        // Size only, never the names. This endpoint is unauthenticated, and the configured hosts
        // are the employers this deployment targets — that list is not public information. An
        // operator verifying which entries loaded reads the startup log, which is access-controlled.
        out.put("extraAllowedHosts", extraAllowedHosts.size());
        return out;
    }
}
