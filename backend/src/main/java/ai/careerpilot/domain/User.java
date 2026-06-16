package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class User {
    @Id @GeneratedValue
    private UUID id;

    @Column(name = "org_id", nullable = false) private UUID orgId;
    @Column(nullable = false, unique = true) private String email;
    @Column(name = "password_hash", nullable = false) private String passwordHash;
    @Column(name = "full_name") private String fullName;
    @Column(nullable = false) private String role;     // USER | ADMIN | OWNER
    @Column(nullable = false) private String status;   // ACTIVE | SUSPENDED
    @Column(name = "email_verified", nullable = false) private boolean emailVerified;
    @Column(name = "last_login_at") private Instant lastLoginAt;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
