package ai.careerpilot.api;

import ai.careerpilot.api.dto.CopilotDtos.ConversationSummary;
import ai.careerpilot.api.dto.CopilotDtos.CopilotStreamRequest;
import ai.careerpilot.api.dto.CopilotDtos.MessageView;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.service.ConversationMemory;
import ai.careerpilot.service.CopilotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The AI Copilot HTTP surface.
 *
 * Streaming uses Server-Sent Events over a normal authenticated POST so the
 * existing {@code Authorization: Bearer} header + {@link ai.careerpilot.security.JwtAuthFilter}
 * apply unchanged (the browser reads the stream via fetch, not EventSource).
 * Events emitted: {@code meta} (conversationId), repeated {@code delta} ({text}),
 * a terminal {@code done}, or {@code error}.
 */
@RestController
@RequestMapping("/api/copilot")
public class CopilotController {

    private static final Logger log = LoggerFactory.getLogger(CopilotController.class);
    /** Generous cap; a turn that runs longer than this is almost certainly stuck. */
    private static final long STREAM_TIMEOUT_MS = 180_000L;

    private final CopilotService copilot;
    private final ConversationMemory memory;

    public CopilotController(CopilotService copilot, ConversationMemory memory) {
        this.copilot = copilot;
        this.memory = memory;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(AuthenticatedUser user, @RequestBody CopilotStreamRequest req) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);

        // streamTurn() runs synchronously (memory resolve, context assembly, prompt
        // build) BEFORE any token streams. A throw here would escape as an HTTP 500 and
        // surface to the user as a generic assistant crash. Catch it and emit a graceful
        // SSE error instead — the assistant must never hard-crash the panel.
        CopilotService.StreamResult result;
        try {
            result = copilot.streamTurn(user, req);
        } catch (Exception e) {
            log.error("Copilot turn setup failed for page={}, action={}", req.page(), req.action(), e);
            send(emitter, "error", Map.of(
                    "message", "I couldn't gather the context for this request. Please try again, "
                            + "or start a new chat if the problem persists."));
            emitter.complete();
            return emitter;
        }

        send(emitter, "meta", Map.of(
                "conversationId", result.conversationId().toString(),
                "sources", result.sources()));

        result.tokens().subscribe(
                token -> send(emitter, "delta", Map.of("text", token)),
                err -> {
                    log.warn("Copilot SSE error: {}", err.toString());
                    send(emitter, "error", Map.of(
                            "message", "I'm temporarily unable to respond right now. Please try again in a moment."));
                    emitter.complete();
                },
                () -> {
                    send(emitter, "done", Map.of(
                            "conversationId", result.conversationId().toString(),
                            "sources", result.sources(),
                            "provider", result.providerRef().get()));
                    emitter.complete();
                });

        emitter.onTimeout(emitter::complete);
        return emitter;
    }

    @GetMapping("/conversations")
    public List<ConversationSummary> conversations(AuthenticatedUser user) {
        return memory.listForUser(user.userId()).stream().map(ConversationSummary::from).toList();
    }

    @GetMapping("/conversations/{id}/messages")
    public List<MessageView> messages(AuthenticatedUser user, @PathVariable UUID id) {
        return memory.messagesFor(id, user.userId()).stream().map(MessageView::from).toList();
    }

    @DeleteMapping("/conversations/{id}")
    public void delete(AuthenticatedUser user, @PathVariable UUID id) {
        memory.delete(id, user.userId());
    }

    /** Best-effort SSE send; a broken pipe (client navigated away) just ends the stream. */
    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // Client disconnected or emitter already completed — nothing to do.
        }
    }
}
