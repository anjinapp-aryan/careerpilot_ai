package ai.careerpilot.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A null/blank exception message previously serialized as {@code message: ""}, which is
 * falsy in JS — clients doing `data.message || fallback` (e.g. the frontend login form)
 * would silently substitute an unrelated fallback string for a genuine 500, making a
 * backend outage look like a credentials failure. generic() must always emit real text.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void nullMessageExceptionYieldsFallbackMessage() {
        ResponseEntity<Map<String, Object>> resp = handler.generic(new RuntimeException());

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        Object message = resp.getBody().get("message");
        assertEquals("Internal server error", message);
    }

    @Test
    void blankMessageExceptionYieldsFallbackMessage() {
        ResponseEntity<Map<String, Object>> resp = handler.generic(new RuntimeException("   "));

        assertEquals("Internal server error", resp.getBody().get("message"));
    }

    @Test
    void realMessageExceptionIsPassedThrough() {
        ResponseEntity<Map<String, Object>> resp = handler.generic(new RuntimeException("db unreachable"));

        Object message = resp.getBody().get("message");
        assertEquals("db unreachable", message);
        assertFalse(((String) message).isBlank());
    }
}
