package ai.careerpilot.jobdiscovery.provider;

import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Shared WebClient construction and lenient JSON-value parsing for HTTP job providers. */
public abstract class AbstractWebJobProvider implements JobProvider {

    protected final WebClient client;

    protected AbstractWebJobProvider(String baseUrl, String userAgent) {
        this.client = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", userAgent)
                .defaultHeader("Accept", "application/json")
                // Some boards return large arrays; lift the default 256KB buffer cap.
                .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .build();
    }

    protected static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    protected static BigDecimal num(Object v) {
        if (v == null) return null;
        try {
            if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
            String s = String.valueOf(v).replaceAll("[^0-9.]", "");
            return s.isBlank() ? null : new BigDecimal(s);
        } catch (Exception e) {
            return null;
        }
    }

    protected static Boolean bool(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    /** Epoch seconds (number or numeric string) → Instant; tolerant of nulls/garbage. */
    protected static Instant epochSeconds(Object v) {
        if (v == null) return null;
        try {
            long s = (v instanceof Number n) ? n.longValue() : Long.parseLong(String.valueOf(v).trim());
            return Instant.ofEpochSecond(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** ISO-8601 timestamp (e.g. "2024-01-15T12:00:00Z" or a local date-time) → Instant; tolerant. */
    protected static Instant isoInstant(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception ignore) {
            try {
                // Adzuna/Jooble sometimes omit the zone: treat as UTC.
                return java.time.LocalDateTime.parse(s).toInstant(java.time.ZoneOffset.UTC);
            } catch (Exception e) {
                return null;
            }
        }
    }

    protected static List<String> stringList(Object v) {
        if (!(v instanceof List<?> list)) return List.of();
        return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> asMap(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
