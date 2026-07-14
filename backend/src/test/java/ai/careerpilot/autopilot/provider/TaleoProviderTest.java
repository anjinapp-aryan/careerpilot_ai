package ai.careerpilot.autopilot.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaleoProviderTest {

    private final TaleoProvider provider = new TaleoProvider();

    @Test
    void nameIsTaleo() {
        assertEquals("taleo", provider.name());
    }

    @Test
    void supportsTaleoNetUrls() {
        assertTrue(provider.supports("https://acme.taleo.net/careersection/jobdetail.ftl?job=123"));
    }

    @Test
    void supportsTbeTaleoNetUrls() {
        assertTrue(provider.supports("https://tbe.taleo.net/careersection/2/jobdetail.ftl?job=456"));
    }

    @Test
    void doesNotSupportUnrelatedHosts() {
        assertFalse(provider.supports("https://boards.greenhouse.io/acme/jobs/123"));
        assertFalse(provider.supports("https://careers.acme.com/apply/1"));
    }

    @Test
    void doesNotSupportNullUrl() {
        assertFalse(provider.supports(null));
    }

    @Test
    void doesNotSupportBlankUrl() {
        assertFalse(provider.supports("   "));
    }

    @Test
    void autoSubmitIsNeverConfigured() {
        assertFalse(provider.autoSubmitConfigured());
    }

    @Test
    void submitAlwaysReturnsHumanReview() {
        ApplicationSubmissionRequest request = new ApplicationSubmissionRequest(
                null, null, "https://acme.taleo.net/careersection/jobdetail.ftl?job=1", null);
        SubmissionResult result = provider.submit(request);
        assertEquals(SubmissionStatus.HUMAN_REVIEW, result.status());
        assertEquals("taleo", result.provider());
        assertNull(result.externalReference());
        assertNotNull(result.reason());
    }

    @Test
    void isNotFallbackProvider() {
        assertFalse(provider.isFallback());
    }
}
