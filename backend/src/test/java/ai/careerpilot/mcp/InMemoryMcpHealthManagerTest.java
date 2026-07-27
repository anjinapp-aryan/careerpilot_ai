package ai.careerpilot.mcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link InMemoryMcpHealthManager} — checks unknown servers report {@link McpHealthStatus#UNKNOWN}
 * by default (nothing heartbeats in this phase) and that a recorded heartbeat is reflected back.
 */
class InMemoryMcpHealthManagerTest {

    private final InMemoryMcpHealthManager health = new InMemoryMcpHealthManager();

    @Test
    void unregisteredServerReportsUnknownHealth() {
        McpServerHealth result = health.healthOf("filesystem");

        assertThat(result.status()).isEqualTo(McpHealthStatus.UNKNOWN);
        assertThat(result.latencyMs()).isEqualTo(-1);
        assertThat(result.lastHeartbeat()).isNull();
    }

    @Test
    void recordedHeartbeatIsReflectedInSubsequentLookup() {
        health.recordHeartbeat("filesystem", McpHealthStatus.UP, 42);

        McpServerHealth result = health.healthOf("filesystem");
        assertThat(result.status()).isEqualTo(McpHealthStatus.UP);
        assertThat(result.latencyMs()).isEqualTo(42);
        assertThat(result.lastHeartbeat()).isNotNull();
    }

    @Test
    void allHealthReflectsEveryRecordedServer() {
        health.recordHeartbeat("filesystem", McpHealthStatus.UP, 10);
        health.recordHeartbeat("postgres", McpHealthStatus.DOWN, 5000);

        assertThat(health.allHealth()).hasSize(2);
    }
}
