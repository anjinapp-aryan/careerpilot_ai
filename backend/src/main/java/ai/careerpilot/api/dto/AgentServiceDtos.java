package ai.careerpilot.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.HashMap;
import java.util.Map;

/** DTOs for agent service responses. */
public final class AgentServiceDtos {

    private AgentServiceDtos() {}

    /**
     * Response from /runs or /runs/resume endpoints.
     * The agent service returns flexible state objects; we capture them as Map<String, Object>.
     */
    public record AgentRunResponse(
            String thread_id,
            String status,
            Map<String, Object> state) {

        /**
         * Builder for flexible Jackson deserialization.
         */
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String thread_id;
            private String status;
            private final Map<String, Object> state = new HashMap<>();

            public Builder threadId(String thread_id) {
                this.thread_id = thread_id;
                return this;
            }

            public Builder status(String status) {
                this.status = status;
                return this;
            }

            public Builder state(Map<String, Object> state) {
                this.state.putAll(state);
                return this;
            }

            public AgentRunResponse build() {
                return new AgentRunResponse(thread_id, status, new HashMap<>(state));
            }
        }
    }
}
