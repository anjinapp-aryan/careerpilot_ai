package ai.careerpilot.memory.conversation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * A dedicated, bounded executor for conversation-intelligence extraction — deliberately NOT the
 * same executor as {@code CareerMemoryExecutorConfig} (fast DB writes) because this one makes an
 * LLM call per user turn, which is orders of magnitude slower; sharing a pool would let a slow
 * extraction queue starve the fast memory-write path, or vice versa. Same shape as every other
 * per-stage executor in this codebase.
 */
@Configuration
public class ConversationIntelligenceExecutorConfig {

    public static final String CONVERSATION_INTELLIGENCE_EXECUTOR = "conversationIntelligenceExecutor";

    @Bean(name = CONVERSATION_INTELLIGENCE_EXECUTOR)
    public ThreadPoolTaskExecutor conversationIntelligenceExecutor(
            @Value("${career.memory.conversation.executor.core-pool-size:2}") int core,
            @Value("${career.memory.conversation.executor.max-pool-size:4}") int max,
            @Value("${career.memory.conversation.executor.queue-capacity:200}") int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix("conv-intel-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
