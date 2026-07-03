package ai.careerpilot.ai.embedding;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-unit coverage for the embedding seam: the flag/provider gate (no behavior when disabled or
 * unconfigured), best-effort failure isolation, source truncation, and the pgvector literal format.
 */
class EmbeddingServiceTest {

    /** Minimal in-memory provider that records the text it was asked to embed. */
    private static final class FakeProvider implements EmbeddingProvider {
        String lastText;
        boolean configured = true;
        boolean blowUp = false;

        @Override public String name() { return "fake"; }
        @Override public boolean isConfigured() { return configured; }
        @Override public int dimensions() { return 3; }
        @Override public float[] embed(String text) {
            this.lastText = text;
            if (blowUp) throw new RuntimeException("boom");
            return new float[]{0.1f, -0.2f, 0.3f};
        }
    }

    @Test
    void disabledFlagIsNoOp() {
        var fake = new FakeProvider();
        var svc = new EmbeddingService(List.of(fake), false, 8000);

        assertFalse(svc.isEnabled());
        assertTrue(svc.embed("hello").isEmpty());
        assertNull(fake.lastText, "provider must not be called when disabled");
    }

    @Test
    void enabledButNoConfiguredProviderIsNoOp() {
        var fake = new FakeProvider();
        fake.configured = false;
        var svc = new EmbeddingService(List.of(fake), true, 8000);

        assertFalse(svc.isEnabled());
        assertEquals(0, svc.dimensions());
        assertTrue(svc.embed("hello").isEmpty());
    }

    @Test
    void embedsWhenEnabledAndConfigured() {
        var fake = new FakeProvider();
        var svc = new EmbeddingService(List.of(fake), true, 8000);

        assertTrue(svc.isEnabled());
        assertEquals(3, svc.dimensions());
        assertArrayEquals(new float[]{0.1f, -0.2f, 0.3f}, svc.embed("hello").orElseThrow());
    }

    @Test
    void blankTextIsNotEmbedded() {
        var fake = new FakeProvider();
        var svc = new EmbeddingService(List.of(fake), true, 8000);

        assertTrue(svc.embed("   ").isEmpty());
        assertNull(fake.lastText);
    }

    @Test
    void providerFailureIsIsolated() {
        var fake = new FakeProvider();
        fake.blowUp = true;
        var svc = new EmbeddingService(List.of(fake), true, 8000);

        assertTrue(svc.embed("hello").isEmpty(), "a provider exception must surface as empty, not throw");
    }

    @Test
    void truncatesToMaxChars() {
        var fake = new FakeProvider();
        var svc = new EmbeddingService(List.of(fake), true, 5);

        svc.embed("abcdefghij");

        assertEquals("abcde", fake.lastText);
    }

    @Test
    void toVectorLiteralFormatsForPgvector() {
        assertEquals("[0.1,-0.2,0.3]", EmbeddingService.toVectorLiteral(new float[]{0.1f, -0.2f, 0.3f}));
        assertEquals("[1.0]", EmbeddingService.toVectorLiteral(new float[]{1.0f}));
    }
}
