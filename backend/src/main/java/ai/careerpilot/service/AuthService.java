package ai.careerpilot.service;

import ai.careerpilot.api.dto.AuthDtos.AuthResponse;
import ai.careerpilot.api.dto.AuthDtos.LoginRequest;
import ai.careerpilot.api.dto.AuthDtos.RegisterRequest;
import ai.careerpilot.domain.Organization;
import ai.careerpilot.domain.Subscription;
import ai.careerpilot.domain.User;
import ai.careerpilot.repo.OrganizationRepository;
import ai.careerpilot.repo.SubscriptionRepository;
import ai.careerpilot.repo.UserRepository;
import ai.careerpilot.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Service
public class AuthService {

    private final UserRepository users;
    private final OrganizationRepository orgs;
    private final SubscriptionRepository subs;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final long accessTtlMinutes;

    public AuthService(UserRepository users, OrganizationRepository orgs, SubscriptionRepository subs,
                       PasswordEncoder encoder, JwtService jwt,
                       @Value("${security.jwt.access-token-ttl-minutes}") long accessTtlMinutes) {
        this.users = users;
        this.orgs = orgs;
        this.subs = subs;
        this.encoder = encoder;
        this.jwt = jwt;
        this.accessTtlMinutes = accessTtlMinutes;
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (users.existsByEmail(req.email().toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(CONFLICT, "Email already registered");
        }
        String slug = slugify(req.organizationName());
        Organization org = orgs.save(Organization.builder()
                .name(req.organizationName()).slug(uniqueSlug(slug)).plan("FREE").build());

        subs.save(Subscription.builder()
                .orgId(org.getId()).plan("FREE").status("ACTIVE").seats(1)
                .currentPeriodStart(Instant.now())
                .currentPeriodEnd(Instant.now().plus(365, ChronoUnit.DAYS))
                .build());

        User u = users.save(User.builder()
                .orgId(org.getId())
                .email(req.email().toLowerCase(Locale.ROOT))
                .passwordHash(encoder.encode(req.password()))
                .fullName(req.fullName())
                .role("OWNER")
                .status("ACTIVE")
                .emailVerified(false)
                .build());

        return token(u);
    }

    @Transactional
    public AuthResponse login(LoginRequest req) {
        User u = users.findByEmail(req.email().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "Invalid credentials"));
        if (!encoder.matches(req.password(), u.getPasswordHash())) {
            throw new ResponseStatusException(UNAUTHORIZED, "Invalid credentials");
        }
        u.setLastLoginAt(Instant.now());
        return token(u);
    }

    private AuthResponse token(User u) {
        String access = jwt.issueAccessToken(u.getId(), u.getOrgId(), u.getEmail(), u.getRole());
        return new AuthResponse(access, "Bearer", accessTtlMinutes, u.getId(), u.getOrgId(),
                u.getEmail(), u.getRole(), u.getFullName());
    }

    private String slugify(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private String uniqueSlug(String base) {
        String slug = base;
        int n = 1;
        while (orgs.findBySlug(slug).isPresent()) {
            slug = base + "-" + n++;
        }
        return slug;
    }
}
