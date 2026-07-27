package ai.careerpilot.mcp.tool;

import ai.careerpilot.memory.CareerMemoryService;
import ai.careerpilot.mcp.McpRegistry;
import ai.careerpilot.mcp.McpConfig;
import ai.careerpilot.mcp.tool.context7.Context7McpServerConfig;
import ai.careerpilot.mcp.tool.filesystem.FilesystemMcpServerConfig;
import ai.careerpilot.mcp.tool.github.GitHubMcpServerConfig;
import ai.careerpilot.mcp.tool.memory.MemoryMcpServerConfig;
import ai.careerpilot.mcp.tool.postgres.PostgresMcpServerConfig;
import ai.careerpilot.repo.ApplicationRepository;
import ai.careerpilot.repo.CareerStrategyRepository;
import ai.careerpilot.repo.JobRecommendationRepository;
import ai.careerpilot.repo.ResumeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Phase 10.2.1 validation sprint — "VERIFY FEATURE FLAGS" section. Each of the 5 Phase 10.2
 * server configs previously only had direct-instantiation unit tests (calling the {@code @Bean}
 * method directly, like {@code NvidiaProviderConfigTest}) — genuine coverage of the
 * registration/handler logic, but no evidence that the {@code @ConditionalOnProperty} gating
 * actually prevents bean construction inside a real Spring context. This closes that gap: for
 * each server, turning its flag (and/or the {@code mcp.enabled} master flag) off means the bean
 * is never constructed at all; turning both on constructs it.
 */
class McpToolServersFeatureFlagTest {

    @Configuration
    static class MockDependencies {
        @Bean ResumeRepository resumeRepository() { return mock(ResumeRepository.class); }
        @Bean JobRecommendationRepository jobRecommendationRepository() { return mock(JobRecommendationRepository.class); }
        @Bean ApplicationRepository applicationRepository() { return mock(ApplicationRepository.class); }
        @Bean CareerStrategyRepository careerStrategyRepository() { return mock(CareerStrategyRepository.class); }
        @Bean CareerMemoryService careerMemoryService() { return mock(CareerMemoryService.class); }
    }

    private ApplicationContextRunner runnerFor(Class<?> serverConfigClass) {
        return new ApplicationContextRunner()
                .withUserConfiguration(MockDependencies.class, McpConfig.class, serverConfigClass);
    }

    @Test
    void filesystemServer_absentWithBothFlagsOff_presentWithBothOn() {
        ApplicationContextRunner runner = runnerFor(FilesystemMcpServerConfig.class);

        runner.run(context -> assertThat(context).doesNotHaveBean(FilesystemMcpServerConfig.class));
        runner.withPropertyValues("mcp.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(FilesystemMcpServerConfig.class));
        runner.withPropertyValues("mcp.filesystem.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(FilesystemMcpServerConfig.class));
        runner.withPropertyValues("mcp.enabled=true", "mcp.filesystem.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(FilesystemMcpServerConfig.class);
                    assertThat(context.getBean(McpRegistry.class).findServer("filesystem")).isPresent();
                });
    }

    @Test
    void postgresServer_absentWithBothFlagsOff_presentWithBothOn() {
        ApplicationContextRunner runner = runnerFor(PostgresMcpServerConfig.class);

        runner.run(context -> assertThat(context).doesNotHaveBean(PostgresMcpServerConfig.class));
        runner.withPropertyValues("mcp.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(PostgresMcpServerConfig.class));
        runner.withPropertyValues("mcp.enabled=true", "mcp.postgres.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(PostgresMcpServerConfig.class);
                    assertThat(context.getBean(McpRegistry.class).findServer("postgres")).isPresent();
                });
    }

    @Test
    void githubServer_absentWithBothFlagsOff_presentWithBothOn() {
        ApplicationContextRunner runner = runnerFor(GitHubMcpServerConfig.class);

        runner.run(context -> assertThat(context).doesNotHaveBean(GitHubMcpServerConfig.class));
        runner.withPropertyValues("mcp.enabled=true", "mcp.github.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(GitHubMcpServerConfig.class);
                    assertThat(context.getBean(McpRegistry.class).findServer("github")).isPresent();
                });
    }

    @Test
    void memoryServer_absentWithBothFlagsOff_presentWithBothOn() {
        ApplicationContextRunner runner = runnerFor(MemoryMcpServerConfig.class);

        runner.run(context -> assertThat(context).doesNotHaveBean(MemoryMcpServerConfig.class));
        runner.withPropertyValues("mcp.enabled=true", "mcp.memory.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(MemoryMcpServerConfig.class);
                    assertThat(context.getBean(McpRegistry.class).findServer("memory")).isPresent();
                });
    }

    @Test
    void context7Server_registeredButToolAbsentWithoutApiKey_toolPresentWithApiKey() {
        ApplicationContextRunner runner = runnerFor(Context7McpServerConfig.class);

        runner.run(context -> assertThat(context).doesNotHaveBean(Context7McpServerConfig.class));
        runner.withPropertyValues("mcp.enabled=true", "mcp.context7.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(Context7McpServerConfig.class);
                    McpRegistry registry = context.getBean(McpRegistry.class);
                    assertThat(registry.findServer("context7")).isPresent();
                    assertThat(registry.findServer("context7").get().enabled()).isFalse();
                    assertThat(registry.findTool("search_documentation")).isEmpty();
                });
        runner.withPropertyValues("mcp.enabled=true", "mcp.context7.enabled=true", "mcp.context7.api-key=real-key")
                .run(context -> {
                    McpRegistry registry = context.getBean(McpRegistry.class);
                    assertThat(registry.findServer("context7").get().enabled()).isTrue();
                    assertThat(registry.findTool("search_documentation")).isPresent();
                });
    }
}
