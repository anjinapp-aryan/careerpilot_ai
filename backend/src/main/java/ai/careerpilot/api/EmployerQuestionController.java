package ai.careerpilot.api;

import ai.careerpilot.domain.EmployerAnswer;
import ai.careerpilot.domain.EmployerQuestion;
import ai.careerpilot.employerquestion.AnswerConfidence;
import ai.careerpilot.employerquestion.AnswerResolution;
import ai.careerpilot.employerquestion.EmployerAnswerService;
import ai.careerpilot.employerquestion.EmployerQuestionService;
import ai.careerpilot.security.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase D — the human review surface for employer answers.
 *
 * <p>Every response is scoped to {@code user.userId()} (manual multi-tenant isolation, this
 * codebase's convention) and returns {@code 200} with {@code enabled:false} when the feature is
 * dark, matching the {@code GET /api/career-timeline} convention so a client can tell "off" from
 * "nothing to review".
 *
 * <p>There is deliberately <b>no endpoint that approves an answer as a side effect of anything
 * else</b>. Approval is one explicit call, made by a human looking at the text.
 */
@RestController
@RequestMapping("/api/employer-questions")
public class EmployerQuestionController {

    private final EmployerQuestionService questions;
    private final EmployerAnswerService answers;

    public EmployerQuestionController(EmployerQuestionService questions, EmployerAnswerService answers) {
        this.questions = questions;
        this.answers = answers;
    }

    /** The deduplicated question library. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> library() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", questions.isEnabled());
        out.put("questions", questions.library().stream().map(EmployerQuestionController::questionView).toList());
        return ResponseEntity.ok(out);
    }

    /**
     * Record a question sighting into the library, deduplicating onto an existing logical question.
     *
     * <p>Observation only — it stores what was asked and never creates or drafts an answer. Without
     * this the library has no way to be populated at all, and the review workflow would have nothing
     * to review.
     */
    @PostMapping("/observe")
    public ResponseEntity<Map<String, Object>> observe(@RequestBody Map<String, Object> body) {
        Optional<EmployerQuestion> recorded = questions.record(new EmployerQuestionService.Observation(
                str(body, "questionText"),
                str(body, "canonicalField"),
                str(body, "questionCategory"),
                str(body, "questionType"),
                Boolean.TRUE.equals(body == null ? null : body.get("required")),
                str(body, "employer"),
                str(body, "atsPlatform")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", questions.isEnabled());
        out.put("recorded", recorded.isPresent());
        recorded.ifPresent(q -> out.put("question", questionView(q)));
        return ResponseEntity.ok(out);
    }

    /**
     * Resolve a question to a usable answer, with the full explanation of why. Read-only: it never
     * creates a draft, so calling it can never manufacture something to approve.
     */
    @PostMapping("/resolve")
    public ResponseEntity<Map<String, Object>> resolve(AuthenticatedUser user,
                                                       @RequestBody Map<String, Object> body) {
        String questionText = str(body, "questionText");
        String canonicalField = str(body, "canonicalField");
        AnswerResolution resolution = answers.resolve(user.userId(), questionText, canonicalField);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", answers.isEnabled());
        out.putAll(resolution.explain());
        return ResponseEntity.ok(out);
    }

    /** Store a draft for review. Always unapproved — see {@code EmployerAnswerService.draft}. */
    @PostMapping("/answers/draft")
    public ResponseEntity<Map<String, Object>> draft(AuthenticatedUser user,
                                                     @RequestBody Map<String, Object> body) {
        UUID questionId = uuid(body, "questionId");
        Optional<EmployerAnswer> saved = answers.draft(user.userId(), questionId,
                str(body, "answerText"),
                AnswerConfidence.parseOrUnknown(str(body, "confidence")),
                str(body, "source"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", answers.isEnabled());
        out.put("drafted", saved.isPresent());
        saved.ifPresent(a -> out.put("answer", answerView(a)));
        return ResponseEntity.ok(out);
    }

    /** The explicit human act that makes an answer reusable. */
    @PostMapping("/answers/{answerId}/approve")
    public ResponseEntity<Map<String, Object>> approve(AuthenticatedUser user,
                                                       @PathVariable UUID answerId) {
        Optional<EmployerAnswer> approved = answers.approve(user.userId(), answerId, user.userId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", answers.isEnabled());
        out.put("approved", approved.isPresent());
        approved.ifPresent(a -> out.put("answer", answerView(a)));
        return ResponseEntity.ok(out);
    }

    /** Everything awaiting a human decision. */
    @GetMapping("/answers/pending")
    public ResponseEntity<Map<String, Object>> pending(AuthenticatedUser user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", answers.isEnabled());
        out.put("pending", answers.pendingReview(user.userId()).stream()
                .map(EmployerQuestionController::answerView).toList());
        return ResponseEntity.ok(out);
    }

    private static Map<String, Object> questionView(EmployerQuestion q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", q.getId());
        m.put("originalText", q.getOriginalText());
        m.put("normalizedText", q.getNormalizedText());
        m.put("canonicalField", q.getCanonicalField());
        m.put("questionCategory", q.getQuestionCategory());
        m.put("questionType", q.getQuestionType());
        m.put("required", q.isRequired());
        m.put("timesSeen", q.getTimesSeen());
        m.put("atsPlatform", q.getAtsPlatform());
        m.put("lastSeenAt", q.getLastSeenAt());
        return m;
    }

    private static Map<String, Object> answerView(EmployerAnswer a) {
        AnswerConfidence confidence = AnswerConfidence.parseOrUnknown(a.getConfidence());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("questionId", a.getQuestionId());
        m.put("answerText", a.getAnswerText());
        m.put("confidence", confidence.name());
        m.put("approved", a.isApproved());
        m.put("approvedAt", a.getApprovedAt());
        m.put("source", a.getSource());
        m.put("usageCount", a.getUsageCount());
        m.put("lastUsedAt", a.getLastUsedAt());
        m.put("usableByAutomation", a.isApproved() && confidence.isUsableByAutomation());
        return m;
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static UUID uuid(Map<String, Object> body, String key) {
        String raw = str(body, key);
        try {
            return raw == null ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
