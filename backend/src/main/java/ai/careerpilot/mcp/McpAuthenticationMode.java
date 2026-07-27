package ai.careerpilot.mcp;

/**
 * Phase 10.1 — declares how a registered {@link McpServerDefinition} authenticates, as
 * metadata only. No credential material is read or stored by this enum; a future server
 * implementation supplies its own concrete authentication per {@link
 * ai.careerpilot.mcp.security.McpAuthenticationProvider}.
 */
public enum McpAuthenticationMode {
    NONE,
    API_KEY,
    OAUTH2,
    MUTUAL_TLS,
    CUSTOM
}
