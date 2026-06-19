package ai.careerpilot.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    @Value("${security.jwt.secret}")
    private String secret;

    @Value("${security.jwt.access-token-ttl-minutes}")
    private long accessTtlMinutes;

    private SecretKey key;

    @PostConstruct
    void init() {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("JWT_SECRET must be set and >= 32 characters");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        // Verification aid: log a NON-reversible fingerprint of the signing secret so
        // operators can confirm every instance/deploy loaded the SAME JWT_SECRET
        // (a changed secret silently invalidates all previously-issued tokens → 401s).
        log.info("JWT signing key initialized — secretLength={} fingerprint={} accessTtlMinutes={}",
                secret.length(), fingerprint(secret), accessTtlMinutes);
    }

    /** First 8 hex chars of SHA-256(secret). Safe to log; cannot reconstruct the secret. */
    private static String fingerprint(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 8);
        } catch (Exception e) {
            return "unavailable";
        }
    }

    public String issueAccessToken(UUID userId, UUID orgId, String email, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .issuer("careerpilot")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtlMinutes, ChronoUnit.MINUTES)))
                .claims(Map.of("org", orgId.toString(), "email", email, "role", role))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
