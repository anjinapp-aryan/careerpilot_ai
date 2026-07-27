package ai.careerpilot.mcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link McpProperties} — every flag must default to {@code false} (dark-by-default). */
class McpPropertiesTest {

    @Test
    void allFlagsDefaultToFalse() {
        McpProperties props = new McpProperties();

        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getDiscovery().isEnabled()).isFalse();
        assertThat(props.getHealth().isEnabled()).isFalse();
    }

    @Test
    void flagsAreIndependentlySettable() {
        McpProperties props = new McpProperties();
        props.setEnabled(true);
        props.getHealth().setEnabled(true);

        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getHealth().isEnabled()).isTrue();
        assertThat(props.getDiscovery().isEnabled()).isFalse();
    }
}
