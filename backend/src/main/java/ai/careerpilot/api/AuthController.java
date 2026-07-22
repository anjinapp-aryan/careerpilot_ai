package ai.careerpilot.api;

import ai.careerpilot.api.dto.AuthDtos.AuthResponse;
import ai.careerpilot.api.dto.AuthDtos.LoginRequest;
import ai.careerpilot.api.dto.AuthDtos.RegisterRequest;
import ai.careerpilot.service.AuditService;
import ai.careerpilot.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AuditService audit;

    public AuthController(AuthService authService, AuditService audit) {
        this.authService = authService;
        this.audit = audit;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest req, HttpServletRequest httpRequest) {
        AuthResponse resp = authService.register(req);
        audit.log(resp.orgId(), resp.userId(), resp.email(), "USER_REGISTERED", "User", resp.userId().toString(), httpRequest, null);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req, HttpServletRequest httpRequest) {
        try {
            AuthResponse resp = authService.login(req);
            audit.log(resp.orgId(), resp.userId(), resp.email(), "LOGIN_SUCCESS", "User", resp.userId().toString(), httpRequest, null);
            return ResponseEntity.ok(resp);
        } catch (ResponseStatusException e) {
            audit.log(null, null, req.email(), "LOGIN_FAILED", "User", null, httpRequest,
                    Map.of("reason", String.valueOf(e.getReason())));
            throw e;
        }
    }
}
