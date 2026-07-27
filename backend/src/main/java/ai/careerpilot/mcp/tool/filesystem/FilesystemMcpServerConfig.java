package ai.careerpilot.mcp.tool.filesystem;

import ai.careerpilot.domain.Resume;
import ai.careerpilot.mcp.McpAuthenticationMode;
import ai.careerpilot.mcp.McpCapability;
import ai.careerpilot.mcp.McpMetrics;
import ai.careerpilot.mcp.McpRegistry;
import ai.careerpilot.mcp.McpServerDefinition;
import ai.careerpilot.mcp.McpToolDefinition;
import ai.careerpilot.mcp.tool.McpToolHandlerRegistry;
import ai.careerpilot.repo.ResumeRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 10.2 — Filesystem MCP server. Purpose per the phase spec: resume upload/parsing/
 * versioning, cover letter generation, generated documents, file reading/writing, document
 * export. This first tool covers the read side only (resume metadata + a text excerpt) by
 * wrapping the existing {@link ResumeRepository} — it does NOT touch S3/MinIO directly and does
 * NOT add any write path; {@code ai.careerpilot.service.ResumeService} remains the only place
 * resumes are uploaded. Gated by BOTH {@code mcp.enabled} and {@code mcp.filesystem.enabled}
 * (both default {@code false}).
 */
@Configuration
@ConditionalOnProperty(prefix = "mcp", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "mcp.filesystem", name = "enabled", havingValue = "true")
public class FilesystemMcpServerConfig {

    private static final String SERVER = "filesystem";
    private static final String TOOL = "get_latest_resume_document";

    @Bean
    public McpServerDefinition filesystemMcpServer(McpRegistry registry,
                                                     McpToolHandlerRegistry handlers,
                                                     McpMetrics metrics,
                                                     ResumeRepository resumes) {
        McpServerDefinition server = new McpServerDefinition(
                SERVER, "1.0.0", Set.of(McpCapability.FILESYSTEM), true, 1, McpAuthenticationMode.NONE);
        registry.registerServer(server);
        metrics.recordServerRegistered(SERVER);

        McpToolDefinition tool = new McpToolDefinition(
                TOOL,
                "Returns filename/content-type/size and a text excerpt for the calling user's most recently uploaded resume.",
                Map.of("type", "object", "properties", Map.of()),
                Map.of("type", "object"),
                McpCapability.FILESYSTEM,
                SERVER);
        registry.registerTool(tool);
        metrics.recordToolRegistered(TOOL);

        handlers.register(TOOL, (args, context) -> handle(context.userId(), resumes));
        return server;
    }

    private Object handle(java.util.UUID userId, ResumeRepository resumes) {
        if (userId == null) {
            return Map.of("available", false, "reason", "no authenticated user in context");
        }
        List<Resume> list = resumes.findByUserIdOrderByCreatedAtDesc(userId);
        if (list.isEmpty()) {
            return Map.of("available", false, "reason", "no resume on file for this user");
        }
        Resume r = list.get(0);
        String parsedText = r.getParsedText();
        String excerpt = parsedText == null ? "" : parsedText.substring(0, Math.min(500, parsedText.length()));
        return Map.of(
                "available", true,
                "filename", r.getFilename() == null ? "" : r.getFilename(),
                "contentType", r.getContentType() == null ? "" : r.getContentType(),
                "sizeBytes", r.getSizeBytes() == null ? 0L : r.getSizeBytes(),
                "createdAt", r.getCreatedAt() == null ? "" : r.getCreatedAt().toString(),
                "parsedTextExcerpt", excerpt);
    }
}
