package ai.careerpilot.service;

import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.api.dto.CopilotDtos.CopilotStreamRequest;
import ai.careerpilot.capability.CapabilityAwareChatService;
import ai.careerpilot.capability.CapabilityDecision;
import ai.careerpilot.capability.CapabilityEngine;
import ai.careerpilot.capability.CapabilityMetrics;
import ai.careerpilot.capability.CapabilityResult;
import ai.careerpilot.capability.CapabilityType;
import ai.careerpilot.domain.CopilotConversation;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.CopilotSkillHandler;
import ai.careerpilot.service.copilot.CopilotSkillRouter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 10.4 — {@link CopilotService}'s new capability-routing branch. Covers the exact
 * requirement "Verify that disabling feature flags restores the original behaviour exactly": the
 * default (flag off, or capability beans absent) path must call {@code AiGatewayService.streamChat}
 * unchanged; only an actual capability match with the flag on routes through {@link
 * CapabilityAwareChatService}, and any failure anywhere in that routing falls back to streaming.
 */
class CopilotServiceTest {

    private final AiGatewayService ai = mock(AiGatewayService.class);
    private final ConversationMemory memory = mock(ConversationMemory.class);
    private final CopilotSkillRouter skillRouter = mock(CopilotSkillRouter.class);
    private final AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
    private final CareerContextRetriever contextRetriever = mock(CareerContextRetriever.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final CapabilityEngine capabilityEngine = mock(CapabilityEngine.class);
    private final CapabilityAwareChatService capabilityAwareChatService = mock(CapabilityAwareChatService.class);
    private final CapabilityMetrics capabilityMetrics = mock(CapabilityMetrics.class);

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> providerFor(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    private CopilotService newService(boolean capabilityIntegrationEnabled,
                                       ObjectProvider<CapabilityEngine> engineProvider,
                                       ObjectProvider<CapabilityAwareChatService> chatServiceProvider) {
        return new CopilotService(ai, memory, skillRouter, orchestrator, contextRetriever, events,
                false, false, 5,
                engineProvider, chatServiceProvider, providerFor(capabilityMetrics),
                capabilityIntegrationEnabled);
    }

    private void stubCommonCollaborators() {
        UUID conversationId = UUID.randomUUID();
        CopilotConversation conv = CopilotConversation.builder().id(conversationId).build();
        when(memory.resolve(any(), any(), any(), any())).thenReturn(conv);
        when(memory.recentHistory(conversationId)).thenReturn(List.of());

        CopilotSkillHandler handler = mock(CopilotSkillHandler.class);
        when(handler.skill()).thenReturn(CopilotSkill.CAREER_GUIDANCE);
        when(handler.systemPrompt(any())).thenReturn("system prompt");
        when(handler.contextBlock(any())).thenReturn("context");
        when(skillRouter.route(any(), any())).thenReturn(handler);
    }

    private AuthenticatedUser user() {
        return new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "u@example.com", "USER");
    }

    private CopilotStreamRequest request(String message) {
        return new CopilotStreamRequest(null, "dashboard", null, message, null);
    }

    @Test
    void flagOff_callsAiGatewayStreamChatUnchanged_neverTouchesCapabilityLayer() {
        stubCommonCollaborators();
        when(ai.streamChat(any(), any(), any())).thenReturn(reactor.core.publisher.Flux.just("hi"));

        CopilotService service = newService(false, providerFor(capabilityEngine), providerFor(capabilityAwareChatService));
        CopilotService.StreamResult result = service.streamTurn(user(), request("hello"));
        result.tokens().blockLast();

        verify(ai).streamChat(any(), any(), any());
        verify(capabilityEngine, never()).analyze(any());
        verify(capabilityAwareChatService, never()).chat(any(), any(), any());
    }

    @Test
    void flagOnButCapabilityBeansAbsent_fallsBackToStreamingUnchanged() {
        stubCommonCollaborators();
        when(ai.streamChat(any(), any(), any())).thenReturn(reactor.core.publisher.Flux.just("hi"));

        CopilotService service = newService(true, providerFor(null), providerFor(null));
        CopilotService.StreamResult result = service.streamTurn(user(), request("hello"));
        result.tokens().blockLast();

        verify(ai).streamChat(any(), any(), any());
    }

    @Test
    void flagOnAndNoCapabilityMatch_stillUsesStreamingPath() {
        stubCommonCollaborators();
        when(capabilityEngine.analyze(any())).thenReturn(CapabilityDecision.noToolNeeded("no capability keyword matched"));
        when(ai.streamChat(any(), any(), any())).thenReturn(reactor.core.publisher.Flux.just("hi"));

        CopilotService service = newService(true, providerFor(capabilityEngine), providerFor(capabilityAwareChatService));
        CopilotService.StreamResult result = service.streamTurn(user(), request("hello there"));
        result.tokens().blockLast();

        verify(ai).streamChat(any(), any(), any());
        verify(capabilityAwareChatService, never()).chat(any(), any(), any());
    }

    @Test
    void flagOnAndCapabilityMatch_routesThroughCapabilityAwareChatServiceInsteadOfStreaming() {
        stubCommonCollaborators();
        when(capabilityEngine.analyze(any())).thenReturn(
                CapabilityDecision.useTools(CapabilityType.GITHUB_REVIEW, List.of(), "matched capability GITHUB_REVIEW"));
        when(capabilityAwareChatService.chat(any(), any(), any())).thenReturn(
                new CapabilityResult(CapabilityType.GITHUB_REVIEW, Map.of(), "ctx", "capability answer",
                        true, true, 42L, null));

        CopilotService service = newService(true, providerFor(capabilityEngine), providerFor(capabilityAwareChatService));
        CopilotService.StreamResult result = service.streamTurn(user(), request("analyse my github"));
        String answer = result.tokens().blockLast();

        assertThat(answer).isEqualTo("capability answer");
        verify(ai, never()).streamChat(any(), any(), any());
        verify(capabilityMetrics).recordEndToEndLatency(anyLong());
    }

    @Test
    void capabilityEngineThrows_fallsBackToStreamingRatherThanFailingRequest() {
        stubCommonCollaborators();
        when(capabilityEngine.analyze(any())).thenThrow(new RuntimeException("boom"));
        when(ai.streamChat(any(), any(), any())).thenReturn(reactor.core.publisher.Flux.just("fallback answer"));

        CopilotService service = newService(true, providerFor(capabilityEngine), providerFor(capabilityAwareChatService));
        CopilotService.StreamResult result = service.streamTurn(user(), request("analyse my github"));
        String answer = result.tokens().blockLast();

        assertThat(answer).isEqualTo("fallback answer");
        verify(ai).streamChat(any(), any(), any());
    }

    @Test
    void capabilityAwareChatServiceThrows_fallsBackToStreamingRatherThanFailingRequest() {
        stubCommonCollaborators();
        when(capabilityEngine.analyze(any())).thenReturn(
                CapabilityDecision.useTools(CapabilityType.GITHUB_REVIEW, List.of(), "matched capability GITHUB_REVIEW"));
        when(capabilityAwareChatService.chat(any(), any(), any())).thenThrow(new RuntimeException("mcp down"));
        when(ai.streamChat(any(), any(), any())).thenReturn(reactor.core.publisher.Flux.just("fallback answer"));

        CopilotService service = newService(true, providerFor(capabilityEngine), providerFor(capabilityAwareChatService));
        CopilotService.StreamResult result = service.streamTurn(user(), request("analyse my github"));
        String answer = result.tokens().blockLast();

        assertThat(answer).isEqualTo("fallback answer");
        verify(ai).streamChat(any(), any(), any());
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}
