package ai.careerpilot.api;

import ai.careerpilot.ai.AiGatewayException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> rse(ResponseStatusException e) {
        return body(HttpStatus.valueOf(e.getStatusCode().value()), e.getReason());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoSuchElementException e) {
        return body(HttpStatus.NOT_FOUND, "Resource not found");
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> forbidden(SecurityException e) {
        return body(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return body(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst().orElse("validation failed");
        return body(HttpStatus.BAD_REQUEST, msg);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> cv(ConstraintViolationException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * Total AI outage — every configured provider failed. Returns a structured
     * 503 the UI can render gracefully (never a stack trace), listing which
     * providers were attempted. Only reached when no provider could serve the
     * request; a single provider success never lands here.
     */
    @ExceptionHandler(AiGatewayException.class)
    public ResponseEntity<Map<String, Object>> aiUnavailable(AiGatewayException e) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("timestamp", Instant.now().toString());
        b.put("status", "AI_PROVIDER_UNAVAILABLE");
        b.put("message", "All configured AI providers are currently unavailable.");
        b.put("providerAttempts", e.getProviderAttempts());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(b);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> generic(Exception e) {
        // e.getMessage() is frequently null for IO/connection-reset style exceptions.
        // A null/empty message renders as "" in the JSON body, which is falsy in JS —
        // clients doing `data.message || fallback` then silently substitute an unrelated
        // fallback string (observed: the frontend login form showing "Invalid email or
        // password" for what was actually a 500). Always emit a real message.
        String message = e.getMessage();
        return body(HttpStatus.INTERNAL_SERVER_ERROR,
                (message == null || message.isBlank()) ? "Internal server error" : message);
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("timestamp", Instant.now().toString());
        b.put("status", status.value());
        b.put("error", status.getReasonPhrase());
        b.put("message", message == null ? "" : message);
        return ResponseEntity.status(status).body(b);
    }
}
