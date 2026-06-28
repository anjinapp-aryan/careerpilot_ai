package ai.careerpilot.service.profile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Tiny, null-safe helpers for the {@code List<String> <-> JSONB string} mapping used across the
 * Candidate Profile module. Centralised so the wire DTO and the service agree on exactly one
 * serialization, and so the conversion is unit-testable in isolation. Never throws — malformed
 * or null input yields an empty list / null column.
 */
public final class JsonLists {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> LIST_OF_STRING = new TypeReference<>() {};

    private JsonLists() {}

    /** Parse a JSON string array into a clean list (trimmed, no blanks). Empty on null/blank/error. */
    public static List<String> toList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> raw = MAPPER.readValue(json, LIST_OF_STRING);
            return raw.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Serialize a list to a JSON array string, or null when empty (so the column stays NULL). */
    public static String toJson(List<String> xs) {
        if (xs == null || xs.isEmpty()) return null;
        List<String> clean = xs.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).toList();
        if (clean.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(clean);
        } catch (Exception e) {
            return null;
        }
    }
}
