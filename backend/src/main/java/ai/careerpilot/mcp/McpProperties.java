package ai.careerpilot.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phase 10.1/10.2 — binds {@code mcp.*}. Every flag defaults {@code false}, matching every other
 * dark-shipped phase in this codebase (see CLAUDE.md's Phase 2D–9 conventions). With every flag
 * at its default, {@link McpConfig} constructs none of its beans and production behavior is
 * unchanged.
 *
 * <pre>
 * mcp.enabled              = false  (master switch — gates McpRegistry/McpExecutor/etc. existing at all)
 * mcp.discovery.enabled    = false  (reserved for a future server auto-discovery mechanism; nothing reads it yet)
 * mcp.health.enabled       = false  (gates McpHealthManager existing at all)
 * mcp.filesystem.enabled   = false  (Phase 10.2 — filesystem MCP server, wraps ResumeRepository)
 * mcp.postgres.enabled     = false  (Phase 10.2 — PostgreSQL MCP server, wraps existing read repositories)
 * mcp.github.enabled       = false  (Phase 10.2 — GitHub MCP server, keyless api.github.com client)
 * mcp.memory.enabled       = false  (Phase 10.2 — Memory MCP server, wraps CareerMemoryService)
 * mcp.context7.enabled     = false  (Phase 10.2 — Context7 MCP server; also requires mcp.context7.api-key)
 * </pre>
 *
 * Each per-server flag only controls whether that server's {@code @Configuration} class attempts
 * to register itself (see e.g. {@code ai.careerpilot.mcp.tool.filesystem.FilesystemMcpServerConfig})
 * — it is independent of {@code mcp.enabled}, which every per-server config additionally requires
 * (both must be {@code true}).
 */
@ConfigurationProperties(prefix = "mcp")
public class McpProperties {

    private boolean enabled = false;
    private final Discovery discovery = new Discovery();
    private final Health health = new Health();
    private final Flag filesystem = new Flag();
    private final Flag postgres = new Flag();
    private final Flag github = new Flag();
    private final Flag memory = new Flag();
    private final Context7 context7 = new Context7();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Discovery getDiscovery() { return discovery; }
    public Health getHealth() { return health; }
    public Flag getFilesystem() { return filesystem; }
    public Flag getPostgres() { return postgres; }
    public Flag getGithub() { return github; }
    public Flag getMemory() { return memory; }
    public Context7 getContext7() { return context7; }

    public static class Discovery {
        private boolean enabled = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Health {
        private boolean enabled = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /** Reusable shape for the simple per-server on/off flags (filesystem/postgres/github/memory). */
    public static class Flag {
        private boolean enabled = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /**
     * Context7 additionally needs an api-key/base-url/timeout, unlike the other four servers —
     * see {@code ai.careerpilot.mcp.tool.context7.Context7ApiClient}. {@code isConfigured()}
     * there is what actually gates real usage; {@code enabled} here only gates whether the
     * server *attempts* to register (matching the "joins the chain only if configured"
     * convention already used by keyed providers like {@code AdzunaProvider}).
     */
    public static class Context7 {
        private boolean enabled = false;
        private String apiKey = "";
        private String baseUrl = "https://context7.com/api";
        private long timeoutMs = 10000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
    }
}
