# System Patterns & Code Templates

## Java Backend Patterns

### Controller Method Pattern
```java
@PostMapping("/endpoint")
public ResponseDto method(AuthenticatedUser user, @RequestBody RequestDto req) {
    // Extract user context
    UUID userId = user.userId();
    UUID orgId = user.orgId();
    
    // Delegate to service
    Entity result = service.doSomething(userId, orgId, req);
    
    // Convert to DTO and return
    return service.toResponse(result);
}
```
**Key points**:
- Accept AuthenticatedUser param (inject via resolver)
- Extract userId/orgId
- Return DTO, not entity

### Service Pattern (Single Seam)
```java
@Service
public class MyService {
    private final AiGatewayService ai;      // ← single LLM entry point
    private final Repository repo;
    
    public Result doSomething(UUID userId, UUID orgId, Request req) {
        // Multi-tenancy check
        Entity entity = repo.findById(req.id()).orElseThrow();
        if (!entity.getUserId().equals(userId)) throw new SecurityException("forbidden");
        
        // Use AiGatewayService, never direct provider
        String feedback = ai.chat(messages, system);
        
        // Persist, return entity (service layer)
        return repo.save(entity);
    }
    
    // DTO conversion (entity → response)
    public ResponseDto toResponse(Entity entity) {
        // Parse JSON fields
        Map<String, Object> state = mapper.readValue(entity.getState(), Map.class);
        return new ResponseDto(..., state, ...);
    }
}
```

### Failover Chain Pattern
```java
public Flux<String> streamChat(List<ChatMessage> messages, String system, 
                               Consumer<String> providerCallback) {
    return streamFrom(orderedConfigured(), 0, messages, system, 
                      props.getDefaultTemperature(), providerCallback);
}

private Flux<String> streamFrom(List<LlmProvider> chain, int idx, 
                                List<ChatMessage> messages, String system, 
                                double temperature, Consumer<String> callback) {
    if (idx >= chain.size()) {
        return Flux.error(new AiGatewayException("All providers exhausted", null));
    }
    
    LlmProvider p = chain.get(idx);
    return p.streamChat(messages, system, temperature)
            .doOnComplete(() -> {
                if (callback != null) callback.accept(p.name());
                healthTracker.recordSuccess(p.name());
            })
            .onErrorResume(err -> {
                healthTracker.recordFailure(p.name(), err.getMessage());
                // Fail over to next provider
                return streamFrom(chain, idx + 1, messages, system, temperature, callback);
            });
}
```
**Key points**:
- Recursive streamFrom() for failover chain
- Callback passed as method param (not ThreadLocal)
- Health tracking on completion/error
- Conditional based on emitted flag for mid-stream failures

### Entity with JSON Field
```java
@Entity
@Table(name = "workflow_runs")
public class WorkflowRun {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    // Multi-tenant isolation key
    @Column(nullable = false)
    private UUID userId;
    
    // Flexible JSON state
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String state;  // ← stored as JSON string, NOT JsonNode
    
    @Temporal(TemporalType.TIMESTAMP)
    @CreationTimestamp
    private Instant createdAt;
}
```
**Key points**:
- @JdbcTypeCode(SqlTypes.JSON) for JSONB columns
- Store as String, parse on read in DTO
- Never use JsonNode as field type

### DTO Record
```java
public final class WorkflowDtos {
    private WorkflowDtos() {}
    
    public record WorkflowRunResponse(
            UUID id,
            String threadId,
            String status,
            Map<String, Object> state,  // ← parsed from JSON, not JsonNode
            Instant createdAt,
            Instant updatedAt) {}
}
```
**Key points**:
- Records for immutability
- Map<String, Object> for flexible JSON
- Sealed package-private inner class

### Streaming with Provider Tracking
```java
@Service
public class CopilotService {
    public StreamResult startStream(...) {
        // Track which provider served the response
        AtomicReference<String> providerRef = new AtomicReference<>("Unknown");
        Consumer<String> callback = name -> providerRef.set(name);
        
        Flux<String> tokens = ai.streamChat(turns, system, callback);
        
        return new StreamResult(conversationId, tokens, sources, providerRef);
    }
}

@RestController
public class CopilotController {
    private void sendDone(SseEmitter emitter, StreamResult result) {
        Map<String, Object> data = Map.of(
            "conversationId", result.conversationId().toString(),
            "provider", result.providerRef().get());  // ← get after stream completes
        send(emitter, "done", data);
    }
}
```
**Key points**:
- AtomicReference holds provider name (set by callback)
- Callback executed in doOnComplete()
- Done event includes actual provider used

### Health Tracking Pattern
```java
@Service
public class ProviderHealthTracker {
    private final Map<String, CachedStatus> cache = new ConcurrentHashMap<>();
    
    public void recordSuccess(String providerName) {
        cache.put(providerName, new CachedStatus(Status.HEALTHY, now() + 5.minutes));
    }
    
    public Status getStatus(String providerName) {
        CachedStatus cached = cache.get(providerName);
        if (cached != null && cached.expiresAt > now()) {
            return cached.status;  // ← still valid
        }
        return Status.UNKNOWN;  // ← expired or never seen
    }
}
```

### Kafka Event Pattern
```java
@Component
public class WorkflowEventProducer {
    private final KafkaTemplate<String, String> kafka;
    
    public void publish(String threadId, Map<String, Object> data) {
        String json = mapper.writeValueAsString(data);
        kafka.send("careerpilot.workflow.events", threadId, json);
    }
}

// Usage in service:
public WorkflowRun save(WorkflowRun run) {
    WorkflowRun saved = repo.save(run);
    events.publish(saved.getThreadId(), 
        Map.of("status", saved.getStatus(), "userId", saved.getUserId()));
    return saved;
}
```

---

## Python Agent Patterns

### Agent Node
```python
async def my_agent_node(state: CareerState) -> dict:
    """
    Reads: prior node outputs (e.g., state['resume_text'], state['jobs'])
    Writes: own keys (e.g., state['my_output'])
    """
    ai = get_ai_provider()  # ← singleton rate-limited provider
    
    schema = {
        "type": "object",
        "properties": {
            "insights": {"type": "string"},
            "score": {"type": "integer"},
        }
    }
    
    response = ai.generate_json(
        prompt="...",
        system="...",
        schema=schema
    )
    
    return {
        "my_output": response["insights"],
        "my_score": response["score"]
    }
```
**Key points**:
- One file per agent under app/agents/
- Returns dict (shallow-merged into CareerState)
- Never instantiate GeminiProvider directly
- Always pass schema for structured output

### Rate-Limited Provider Access
```python
from app.ai_provider import get_ai_provider

async def some_agent(state: CareerState) -> dict:
    ai = get_ai_provider()  # ← decorated with RateLimitedAIProvider
    # Automatically enforces: RPM bucket, TPM bucket, min spacing, 429/503 retry
    response = await ai.generate_response(prompt, system)
    return {"output": response}
```

### Node Interrupt for Human Loop
```python
async def human_approval_node(state: CareerState) -> dict:
    if some_condition:
        raise NodeInterrupt("Please review the plan before continuing")
    
    return {"approved": True}

# Frontend or backend calls /runs/resume to continue
# Backend calls graph.update_state(thread_id, {"decision": "approved"})
# Then graph.invoke(None, config)
```

---

## Frontend Patterns

### API Call with Auth Interceptor
```typescript
// lib/api.ts
import axios from 'axios';
import { authStore } from './auth';

const api = axios.create({
    baseURL: import.meta.env.VITE_API_BASE_URL
});

api.interceptors.request.use(config => {
    const token = authStore(state => state.token);
    if (token) {
        config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
});

api.interceptors.response.use(
    response => response,
    error => {
        if (error.response?.status === 401) {
            authStore.setState({ token: null });
        }
        return Promise.reject(error);
    }
);
```

### Server State with TanStack Query
```typescript
// Usage in component
const { data: workflows } = useQuery({
    queryKey: ['workflows'],
    queryFn: () => api.get('/api/workflows').then(r => r.data),
});

const mutation = useMutation({
    mutationFn: (req) => api.post('/api/workflows/run', req),
    onSuccess: () => {
        // Explicit refetch after mutation
        queryClient.invalidateQueries(['workflows']);
    }
});
```

### SSE Stream Consumption
```typescript
// lib/copilotStream.ts
export async function* streamCopilot(prompt: string) {
    const response = await fetch('/api/copilot/stream', {
        method: 'POST',
        headers: { 'Authorization': `Bearer ${token}` },
        body: JSON.stringify({ prompt })
    });
    
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    
    try {
        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            
            const text = decoder.decode(value, { stream: true });
            const lines = text.split('\n');
            
            for (const line of lines) {
                if (line.startsWith('data: ')) {
                    const data = JSON.parse(line.slice(6));
                    if (data.type === 'token') {
                        yield data.content;
                    } else if (data.type === 'done') {
                        return { provider: data.provider };
                    }
                }
            }
        }
    } finally {
        reader.releaseLock();
    }
}
```

---

## Testing Patterns

### Java Service Test (Template)
```java
@SpringBootTest
class MyServiceTest {
    @MockBean private Repository repo;
    @Autowired private MyService service;
    
    @Test
    void shouldIsolateTenant() {
        UUID userId = UUID.randomUUID();
        Entity entity = Entity.builder().userId(UUID.randomUUID()).build();
        
        when(repo.findById(any())).thenReturn(Optional.of(entity));
        
        assertThrows(SecurityException.class, 
            () -> service.doSomething(userId, UUID.randomUUID(), req));
    }
}
```

### Python Agent Test (Template)
```python
import pytest
from app.agents.my_agent import my_agent_node
from app.state import CareerState

@pytest.mark.asyncio
async def test_agent_formats_output():
    state: CareerState = {
        "resume_text": "...",
        "user_id": "user-123"
    }
    
    result = await my_agent_node(state)
    
    assert "my_output" in result
    assert isinstance(result["my_score"], int)
```

---

## Logging Pattern

```java
// Always use slf4j Logger
private static final Logger log = LoggerFactory.getLogger(MyClass.class);

// Structured logging
log.info("Workflow Created: user={}, target_role={}, jobs={}",
    userId, req.targetRole(), jobCount);

log.warn("Provider {} circuit opened, failing over", providerName);

log.error("All providers exhausted for operation '{}'", operation, e);
```
