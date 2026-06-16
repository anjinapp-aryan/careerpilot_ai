package ai.careerpilot.security;

import java.util.UUID;

public record AuthenticatedUser(UUID userId, UUID orgId, String email, String role) {}
