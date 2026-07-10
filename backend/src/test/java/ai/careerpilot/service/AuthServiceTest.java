package ai.careerpilot.service;

import ai.careerpilot.api.dto.AuthDtos.AuthResponse;
import ai.careerpilot.api.dto.AuthDtos.LoginRequest;
import ai.careerpilot.domain.User;
import ai.careerpilot.repo.OrganizationRepository;
import ai.careerpilot.repo.SubscriptionRepository;
import ai.careerpilot.repo.UserRepository;
import ai.careerpilot.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Regression coverage for the "second device login fails right after the first" report:
 * investigation found login is fully stateless (no session/device table, no lock, no
 * @Version on User) so nothing in this service can make one login attempt fail because
 * of a prior one. These tests pin that down — two sequential logins for the same user
 * both succeed and each mints its own independent token.
 */
class AuthServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final OrganizationRepository orgs = mock(OrganizationRepository.class);
    private final SubscriptionRepository subs = mock(SubscriptionRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final JwtService jwt = mock(JwtService.class);

    private final AuthService service = new AuthService(users, orgs, subs, encoder, jwt, 60L);

    @Test
    void sequentialLoginsForSameUserBothSucceedWithIndependentTokens() {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        User u = User.builder()
                .id(userId).orgId(orgId).email("a@b.com").passwordHash("hash")
                .fullName("A B").role("OWNER").status("ACTIVE").emailVerified(true)
                .build();

        when(users.findByEmail("a@b.com")).thenReturn(Optional.of(u));
        when(encoder.matches(eq("secret"), eq("hash"))).thenReturn(true);
        when(jwt.issueAccessToken(eq(userId), eq(orgId), eq("a@b.com"), eq("OWNER")))
                .thenReturn("token-1", "token-2");

        LoginRequest req = new LoginRequest("a@b.com", "secret");

        AuthResponse first = service.login(req);
        AuthResponse second = service.login(req);

        assertEquals("token-1", first.accessToken());
        assertEquals("token-2", second.accessToken());
        assertNotEquals(first.accessToken(), second.accessToken());
        // No lock, no version bump, no session write blocks the second call — it reaches
        // the same code path and mints a token exactly like the first.
        verify(jwt, times(2)).issueAccessToken(any(), any(), any(), any());
    }

    @Test
    void wrongPasswordAlwaysRejectsRegardlessOfPriorSuccessfulLogin() {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        User u = User.builder()
                .id(userId).orgId(orgId).email("a@b.com").passwordHash("hash")
                .fullName("A B").role("OWNER").status("ACTIVE").emailVerified(true)
                .build();

        when(users.findByEmail("a@b.com")).thenReturn(Optional.of(u));
        when(encoder.matches(eq("secret"), eq("hash"))).thenReturn(true);
        when(encoder.matches(eq("wrong"), eq("hash"))).thenReturn(false);
        when(jwt.issueAccessToken(any(), any(), any(), any())).thenReturn("token-1");

        service.login(new LoginRequest("a@b.com", "secret"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.login(new LoginRequest("a@b.com", "wrong")));
        assertEquals(401, ex.getStatusCode().value());
    }
}
