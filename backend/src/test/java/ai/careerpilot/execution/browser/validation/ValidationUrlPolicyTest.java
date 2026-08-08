package ai.careerpilot.execution.browser.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 12C.5 — the SSRF guard. The harness makes the server fetch a caller-supplied URL with a
 * full browser, so without this class "validate this careers page" is also "screenshot my instance
 * metadata for me". Most of these tests assert a <em>denial</em>.
 */
class ValidationUrlPolicyTest {

    private static ValidationUrlPolicy enforcing() {
        return new ValidationUrlPolicy(true, "", true);
    }

    // ── the allow-list ──

    @Test
    void knownAtsHostsArePermitted() {
        ValidationUrlPolicy policy = enforcing();
        assertThat(policy.evaluate("https://boards.greenhouse.io/acme/jobs/123").allowed()).isTrue();
        assertThat(policy.evaluate("https://jobs.lever.co/acme/abc").allowed()).isTrue();
        assertThat(policy.evaluate("https://jobs.ashbyhq.com/acme/xyz").allowed()).isTrue();
    }

    /**
     * {@code example.com} is IANA-reserved for exactly this purpose and resolves publicly, which
     * the address check requires. Subdomains of it (e.g. {@code careers.example.com}) deliberately
     * do <em>not</em> resolve, so they are only used below where a denial is the expectation.
     */
    @Test
    void anUnknownHostIsDeniedUnlessExplicitlyAllowed() {
        assertThat(enforcing().evaluate("https://example.com/apply").allowed()).isFalse();

        ValidationUrlPolicy withHost = new ValidationUrlPolicy(true, "example.com", true);
        assertThat(withHost.evaluate("https://example.com/apply").allowed()).isTrue();
    }

    @Test
    void anAllowListEntryDoesNotMatchAnUnrelatedSuffix() {
        // "notexample.com" must NOT match "example.com" — a suffix check without the dot would
        // match it, which would silently widen the allow-list to any host ending in those letters.
        ValidationUrlPolicy policy = new ValidationUrlPolicy(true, "example.com", true);
        ValidationUrlPolicy.Verdict verdict = policy.evaluate("https://notexample.com/x");
        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.reason()).contains("not a known ATS");
    }

    @Test
    void anUnknownHostIsRejectedWithoutEvenResolvingIt() {
        // Name check runs before the DNS lookup, so an attacker-supplied hostname never triggers a
        // lookup from this server. The reason proves which check fired.
        ValidationUrlPolicy.Verdict verdict = enforcing().evaluate("https://attacker-controlled.test/x");
        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.reason()).contains("not a known ATS");
    }

    // ── the address check ──

    @Test
    void loopbackIsAlwaysDeniedEvenWithTheAllowListOff() {
        ValidationUrlPolicy permissive = new ValidationUrlPolicy(false, "", false);
        assertThat(permissive.evaluate("http://127.0.0.1:8080/actuator").allowed()).isFalse();
        assertThat(permissive.evaluate("http://localhost:8080/").allowed()).isFalse();
    }

    @Test
    void theCloudMetadataEndpointIsDenied() {
        // 169.254.169.254 is where AWS/GCP/Azure/Oracle serve instance credentials.
        ValidationUrlPolicy permissive = new ValidationUrlPolicy(false, "", false);
        ValidationUrlPolicy.Verdict verdict = permissive.evaluate("http://169.254.169.254/latest/meta-data/");
        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.reason()).containsAnyOf("metadata", "non-public");
    }

    @Test
    void theInternalDockerNetworkIsDenied() {
        // These are the compose service names this backend can genuinely reach.
        ValidationUrlPolicy permissive = new ValidationUrlPolicy(false, "", false);
        assertThat(permissive.evaluate("http://10.0.0.5:9092/").allowed()).isFalse();
        assertThat(permissive.evaluate("http://192.168.1.10/").allowed()).isFalse();
        assertThat(permissive.evaluate("http://172.16.0.3:9000/").allowed()).isFalse();
    }

    @Test
    void anAllowListedHostResolvingPrivatelyIsStillDenied() {
        // DNS rebinding: the name passes the allow-list, the address does not. If the allow-list
        // were trusted here it would BE the vulnerability rather than the control.
        ValidationUrlPolicy policy = new ValidationUrlPolicy(true, "localhost", true);
        assertThat(policy.evaluate("https://localhost/apply").allowed()).isFalse();
    }

    @Test
    void anUnresolvableHostIsDeniedRatherThanPassed() {
        ValidationUrlPolicy policy = new ValidationUrlPolicy(false, "", false);
        assertThat(policy.evaluate("http://this-host-does-not-exist.invalid/").allowed()).isFalse();
    }

    // ── scheme ──

    @Test
    void nonHttpsSchemesAreDeniedByDefault() {
        ValidationUrlPolicy policy = enforcing();
        assertThat(policy.evaluate("http://boards.greenhouse.io/x").allowed()).isFalse();
        assertThat(policy.evaluate("file:///etc/passwd").allowed()).isFalse();
        assertThat(policy.evaluate("data:text/html,<h1>x</h1>").allowed()).isFalse();
        assertThat(policy.evaluate("javascript:alert(1)").allowed()).isFalse();
    }

    @Test
    void fileAndDataSchemesAreDeniedEvenWhenHttpsIsNotRequired() {
        ValidationUrlPolicy policy = new ValidationUrlPolicy(false, "", false);
        assertThat(policy.evaluate("file:///etc/passwd").allowed()).isFalse();
        assertThat(policy.evaluate("data:text/html,x").allowed()).isFalse();
    }

    // ── malformed input ──

    @Test
    void malformedInputIsDeniedWithAReasonAndNeverThrows() {
        ValidationUrlPolicy policy = enforcing();
        assertThat(policy.evaluate(null).allowed()).isFalse();
        assertThat(policy.evaluate("").allowed()).isFalse();
        assertThat(policy.evaluate("not a url at all").allowed()).isFalse();
        assertThat(policy.evaluate("https://").allowed()).isFalse();
        assertThat(policy.evaluate(null).reason()).isNotBlank();
    }

    @Test
    void everyDenialCarriesAReason() {
        ValidationUrlPolicy policy = enforcing();
        for (String url : new String[]{null, "", "http://x", "file:///x", "https://unknown-host.test/x"}) {
            ValidationUrlPolicy.Verdict verdict = policy.evaluate(url);
            if (!verdict.allowed()) {
                assertThat(verdict.reason()).as("denial for %s must explain itself", url).isNotBlank();
            }
        }
    }

    @Test
    void describeReportsThePolicyWithoutLeakingAllowListedHostnames() {
        ValidationUrlPolicy policy = new ValidationUrlPolicy(true, "secret-client.example.com", true);
        String described = String.valueOf(policy.describe());
        assertThat(described).doesNotContain("secret-client.example.com");
        assertThat(policy.describe()).containsEntry("extraAllowedHosts", 1);
    }

    // ── P1: custom-domain ATS front-ends, without weakening the control ──

    @Test
    void aCustomDomainAtsFrontEndIsAdmittedWhenConfigured() {
        // Measured in the F5 audit: 7 of 16 sampled Greenhouse employers serve their board from
        // their own domain, so 24% of real postings were refused. Naming the host fixes that.
        ValidationUrlPolicy policy = new ValidationUrlPolicy(true,
                "careers.airbnb.com,jobs.dropbox.com,instacart.careers", true);

        assertThat(policy.evaluate("https://careers.airbnb.com/positions/8053756").allowed()).isTrue();
        assertThat(policy.evaluate("https://jobs.dropbox.com/listing/7822807").allowed()).isTrue();
        assertThat(policy.evaluate("https://instacart.careers/job/?gh_jid=1").allowed()).isTrue();
    }

    @Test
    void configuringOneCustomDomainDoesNotAdmitAnother() {
        ValidationUrlPolicy policy = new ValidationUrlPolicy(true, "careers.airbnb.com", true);

        assertThat(policy.evaluate("https://careers.someoneelse.com/jobs/1").allowed()).isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "a dangerously broad entry ''{0}'' is ignored")
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "com", "io", "net", "org", "co", "co.uk", "careers", "jobs"})
    void dangerouslyBroadAllowListEntriesAreRejected(String entry) {
        // Suffix matching means a single bare-TLD entry would admit every host on the internet,
        // turning the SSRF control into an SSRF vector. A typo must not do that silently.
        ValidationUrlPolicy policy = new ValidationUrlPolicy(true, entry, true);

        assertThat(policy.describe()).containsEntry("extraAllowedHosts", 0);
        assertThat(policy.evaluate("https://example.com/careers").allowed()).isFalse();
    }

    @Test
    void malformedAllowListEntriesAreDroppedNotAppliedLiterally() {
        ValidationUrlPolicy policy = new ValidationUrlPolicy(true,
                "https://careers.acme.com/jobs, *.acme.com, acme.com:8080, careers.good.com", true);

        // Only the one well-formed bare hostname survives. Asserted on the parsed count rather than
        // by evaluating a URL, because evaluation also performs a DNS lookup and these are not real
        // hosts — that would test the resolver, not the parser.
        assertThat(policy.describe()).containsEntry("extraAllowedHosts", 1);
    }

    @Test
    void aCustomDomainStillCannotResolveIntoAPrivateRange() {
        // The address check is not disableable and runs for allow-listed hosts too — this is the
        // DNS-rebinding case that would otherwise make the allow-list the vulnerability.
        ValidationUrlPolicy policy = new ValidationUrlPolicy(true, "localtest.me", true);

        // localtest.me resolves to 127.0.0.1 by design.
        assertThat(policy.evaluate("https://localtest.me/anything").allowed()).isFalse();
    }

    @Test
    void aCompanySubdomainOfGreenhouseNeedsNoConfiguration() {
        // company.greenhouse.io is recognised by host-suffix match, so it never needed an
        // allowed-hosts entry. Asserted on detection rather than evaluate(), which would also
        // require the fictional host to resolve in DNS.
        assertThat(AtsPlatform.detect("https://acme.greenhouse.io/jobs/1"))
                .isEqualTo(AtsPlatform.GREENHOUSE);
        assertThat(AtsPlatform.detect("https://careers.acme.lever.co/x")).isEqualTo(AtsPlatform.LEVER);
        // ...while a lookalike that merely contains the token is not.
        assertThat(AtsPlatform.detect("https://boards.greenhouse.io.evil.com/x"))
                .isEqualTo(AtsPlatform.UNKNOWN);
    }

    // ── platform detection ──

    @Test
    void platformDetectionMatchesOnHostOnly() {
        assertThat(AtsPlatform.detect("https://boards.greenhouse.io/x")).isEqualTo(AtsPlatform.GREENHOUSE);
        assertThat(AtsPlatform.detect("https://acme.myworkdayjobs.com/x")).isEqualTo(AtsPlatform.WORKDAY);
        // A path containing a vendor name must not file this page under that vendor.
        assertThat(AtsPlatform.detect("https://careers.example.com/greenhouse.io/apply"))
                .isEqualTo(AtsPlatform.UNKNOWN);
        assertThat(AtsPlatform.detect("not a url")).isEqualTo(AtsPlatform.UNKNOWN);
        assertThat(AtsPlatform.detect(null)).isEqualTo(AtsPlatform.UNKNOWN);
    }
}
