package ai.careerpilot.mcp.tool.filesystem;

import ai.careerpilot.domain.Resume;
import ai.careerpilot.mcp.InMemoryMcpMetrics;
import ai.careerpilot.mcp.InMemoryMcpRegistry;
import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.mcp.McpServerDefinition;
import ai.careerpilot.mcp.tool.McpToolHandler;
import ai.careerpilot.mcp.tool.McpToolHandlerRegistry;
import ai.careerpilot.repo.ResumeRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FilesystemMcpServerConfig} — verifies it registers its server/tool into {@link
 * InMemoryMcpRegistry} and that the registered handler correctly returns the caller's most
 * recent resume, degrading gracefully when there's no user context or no resume on file.
 */
class FilesystemMcpServerConfigTest {

    private final InMemoryMcpRegistry registry = new InMemoryMcpRegistry();
    private final McpToolHandlerRegistry handlers = new McpToolHandlerRegistry();
    private final InMemoryMcpMetrics metrics = new InMemoryMcpMetrics();
    private final ResumeRepository resumes = mock(ResumeRepository.class);
    private final FilesystemMcpServerConfig config = new FilesystemMcpServerConfig();

    @Test
    void registersServerAndToolWithHandler() {
        McpServerDefinition server = config.filesystemMcpServer(registry, handlers, metrics, resumes);

        assertThat(server.name()).isEqualTo("filesystem");
        assertThat(registry.findServer("filesystem")).isPresent();
        assertThat(registry.findTool("get_latest_resume_document")).isPresent();
        assertThat(handlers.find("get_latest_resume_document")).isPresent();
        assertThat(metrics.registeredServerCount()).isEqualTo(1);
        assertThat(metrics.registeredToolCount()).isEqualTo(1);
    }

    @Test
    void handlerReturnsLatestResumeMetadataForAuthenticatedUser() {
        UUID userId = UUID.randomUUID();
        Resume resume = Resume.builder()
                .userId(userId).orgId(UUID.randomUUID())
                .filename("resume.pdf").contentType("application/pdf")
                .sizeBytes(1024L).parsedText("Experienced software engineer...")
                .createdAt(Instant.now())
                .build();
        when(resumes.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(resume));

        config.filesystemMcpServer(registry, handlers, metrics, resumes);
        McpToolHandler handler = handlers.find("get_latest_resume_document").orElseThrow();
        McpExecutionContext context = new McpExecutionContext(userId, null, null, "trace", Duration.ofSeconds(5), Map.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.handle(Map.of(), context);

        assertThat(result.get("available")).isEqualTo(true);
        assertThat(result.get("filename")).isEqualTo("resume.pdf");
    }

    @Test
    void handlerDegradesGracefullyWhenNoResumeOnFile() {
        UUID userId = UUID.randomUUID();
        when(resumes.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());

        config.filesystemMcpServer(registry, handlers, metrics, resumes);
        McpToolHandler handler = handlers.find("get_latest_resume_document").orElseThrow();
        McpExecutionContext context = new McpExecutionContext(userId, null, null, "trace", Duration.ofSeconds(5), Map.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.handle(Map.of(), context);

        assertThat(result.get("available")).isEqualTo(false);
    }

    @Test
    void handlerDegradesGracefullyWhenNoAuthenticatedUser() {
        config.filesystemMcpServer(registry, handlers, metrics, resumes);
        McpToolHandler handler = handlers.find("get_latest_resume_document").orElseThrow();
        McpExecutionContext context = new McpExecutionContext(null, null, null, "trace", Duration.ofSeconds(5), Map.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.handle(Map.of(), context);

        assertThat(result.get("available")).isEqualTo(false);
    }
}
