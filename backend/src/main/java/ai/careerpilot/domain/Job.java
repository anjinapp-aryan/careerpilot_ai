package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "jobs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Job {
    @Id @GeneratedValue
    private UUID id;

    @Column(name = "org_id") private UUID orgId;
    @Column(nullable = false) private String title;
    @Column(nullable = false) private String company;
    private String location;
    @Column(nullable = false, columnDefinition = "text") private String description;
    @Column(name = "salary_range") private String salaryRange;
    private String source;
    @Column(name = "external_url") private String externalUrl;
    @Column(name = "posted_at") private Instant postedAt;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
