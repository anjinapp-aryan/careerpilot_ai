package ai.careerpilot.execution.browser.form;

import ai.careerpilot.submission.question.QuestionCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Phase 12C — turns discovered DOM fields into a {@link FormFillPlan}. Pure decision-making: no
 * browser, no I/O beyond the resolver's own up-front context load, no AI.
 *
 * <p>Ordering within the pipeline is discover → classify → resolve → plan, and each stage is a
 * separate, independently-testable component ({@code FormFieldDiscovery} /
 * {@link FieldClassifier} / {@link AnswerResolver} / this). That is the "one reusable engine, not
 * one uploader per ATS" requirement expressed structurally: nothing here mentions Greenhouse,
 * Lever, Ashby or any other vendor.
 */
@Service
public class FormFillPlanner {

    private static final Logger log = LoggerFactory.getLogger(FormFillPlanner.class);

    private final FieldClassifier classifier;
    private final AnswerResolver resolver;

    public FormFillPlanner(FieldClassifier classifier, AnswerResolver resolver) {
        this.classifier = classifier;
        this.resolver = resolver;
    }

    /**
     * Files are supplied by the caller (resolved from the {@code ApplicationPackage}) rather than
     * looked up here, so the planner stays free of package/storage concerns and can be tested with
     * plain values.
     *
     * @param resumeAvailable      a tailored resume file is genuinely available to upload
     * @param coverLetterAvailable a generated cover letter is genuinely available
     * @param coverLetterText      the cover letter body, for textarea-style fields; may be null
     */
    public record Documents(boolean resumeAvailable, boolean coverLetterAvailable, String coverLetterText) {
        public static Documents none() {
            return new Documents(false, false, null);
        }
    }

    public FormFillPlan plan(List<DiscoveredField> discovered, UUID userId, UUID sessionId, Documents documents) {
        if (discovered == null || discovered.isEmpty()) return FormFillPlan.empty();
        Documents docs = documents == null ? Documents.none() : documents;

        AnswerResolver.ResolutionContext ctx;
        try {
            ctx = resolver.loadContext(userId, sessionId);
        } catch (Exception e) {
            // A failed context load must not become "fill nothing and submit" — every field
            // resolves unresolved below, which makes required ones blocking gaps and stops the run.
            log.warn("FORM_PLANNER context load failed user={}: {}", userId, e.toString());
            ctx = AnswerResolver.ResolutionContext.empty();
        }

        List<FormFillPlan.PlannedFill> fills = new ArrayList<>();
        List<FormFillPlan.UnresolvedField> unresolved = new ArrayList<>();

        for (DiscoveredField field : discovered) {
            CanonicalField canonical = classifier.classify(field);

            if (!field.isFillable()) {
                // Hidden/disabled/read-only controls are skipped silently unless they are required,
                // in which case the form is telling us something we cannot satisfy and it must
                // surface as a gap rather than vanish.
                if (field.required()) {
                    unresolved.add(new FormFillPlan.UnresolvedField(field, canonical,
                            "field is required but not fillable (hidden, disabled or read-only)", true));
                }
                continue;
            }

            ResolvedValue resolved = resolveFor(field, canonical, ctx, docs);
            if (resolved.isResolved()) {
                fills.add(new FormFillPlan.PlannedFill(field, canonical, resolved.value(), resolved.source()));
            } else {
                unresolved.add(new FormFillPlan.UnresolvedField(field, canonical,
                        resolved.reason(), field.required()));
            }
        }

        FormFillPlan plan = new FormFillPlan(fills, unresolved);
        log.info("FORM_PLANNER user={} planned={} unresolved={} blocking={}",
                userId, fills.size(), unresolved.size(), plan.blockingGaps().size());
        return plan;
    }

    /**
     * Documents resolve from the package; everything else from {@link AnswerResolver}. File fields
     * carry a sentinel value rather than a path — the engine substitutes the real path at execution
     * time — so the plan (which may be logged) never contains a filesystem location.
     */
    private ResolvedValue resolveFor(DiscoveredField field, CanonicalField canonical,
                                     AnswerResolver.ResolutionContext ctx, Documents docs) {
        switch (canonical) {
            case RESUME_UPLOAD:
                return docs.resumeAvailable()
                        ? ResolvedValue.of(DocumentSlot.RESUME.token(), "ApplicationPackage.resumeId")
                        : ResolvedValue.unresolved("no tailored resume available in the application package");
            case COVER_LETTER_UPLOAD:
                return docs.coverLetterAvailable()
                        ? ResolvedValue.of(DocumentSlot.COVER_LETTER.token(), "ApplicationPackage.coverLetterId")
                        : ResolvedValue.unresolved("no generated cover letter available in the application package");
            case COVER_LETTER_TEXT:
                String text = docs.coverLetterText();
                if (text == null || text.isBlank()) {
                    return ResolvedValue.unresolved("no generated cover letter text available");
                }
                return ResolvedValue.of(fitToLimit(text, field), "CoverLetter.content");
            default:
                QuestionCategory category = classifier.questionCategoryOf(field);
                ResolvedValue value = resolver.resolve(canonical, ctx, category, field.displayName());
                if (!value.isResolved()) return value;
                return adaptToControl(value, field);
        }
    }

    /**
     * Adapts a resolved value to the control that will receive it.
     *
     * <p>The important case is a choice control: a stored answer is prose ("I would require visa
     * sponsorship to work in the EU"), while a {@code <select>} accepts only its own option labels.
     * Rather than guess which option the prose means, this matches the value against the real
     * option list and — if nothing matches — <b>refuses</b>. Picking the closest-looking option
     * would be exactly the fabrication this phase forbids, on a legally significant question.
     */
    private static ResolvedValue adaptToControl(ResolvedValue value, DiscoveredField field) {
        FieldControlType type = field.controlType();
        if (!type.isChoice()) {
            return type == FieldControlType.TEXT || type == FieldControlType.TEXTAREA
                    ? ResolvedValue.of(fitToLimit(value.value(), field), value.source())
                    : value;
        }

        List<String> options = field.options();
        if (options.isEmpty()) {
            // A checkbox/radio with no enumerated options: only a clear boolean can drive it.
            Boolean bool = asBoolean(value.value());
            if (bool == null) {
                return ResolvedValue.unresolved(
                        "value is not a yes/no answer and the control offers no options to match against");
            }
            return ResolvedValue.of(bool ? "true" : "false", value.source());
        }

        String match = matchOption(value.value(), options);
        if (match == null) {
            return ResolvedValue.unresolved(
                    "no option matches the verified answer (options: " + String.join(" | ", options) + ")");
        }
        return ResolvedValue.of(match, value.source() + " → option \"" + match + "\"");
    }

    /**
     * Match a value to one of the control's options: exact, then case-insensitive, then a
     * yes/no mapping. Deliberately does NOT do fuzzy or substring matching — "No" is a substring of
     * "Not applicable", and on a visa-sponsorship question that difference is material.
     */
    static String matchOption(String value, List<String> options) {
        if (value == null) return null;
        String v = value.trim();
        for (String o : options) {
            if (o != null && o.trim().equals(v)) return o;
        }
        for (String o : options) {
            if (o != null && o.trim().equalsIgnoreCase(v)) return o;
        }
        Boolean bool = asBoolean(v);
        if (bool == null) return null;
        for (String o : options) {
            if (o == null) continue;
            Boolean optionBool = asBoolean(o.trim());
            if (optionBool != null && optionBool.equals(bool)) return o;
        }
        return null;
    }

    /** Strict boolean reading. Anything ambiguous returns null rather than defaulting to false. */
    static Boolean asBoolean(String value) {
        if (value == null) return null;
        String v = value.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "true", "yes", "y", "1" -> Boolean.TRUE;
            case "false", "no", "n", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    /**
     * Respects a field's {@code maxlength}. Without this, a 2000-character cover letter typed into
     * a 500-character textarea is silently clipped mid-word <em>by the browser</em> — the employer
     * receives a sentence that stops dead, and nothing in our logs shows it happened.
     *
     * <p>Truncates at the last word boundary within the limit, and appends nothing (an ellipsis
     * would be text the candidate did not write). Returns the value unchanged when it fits or when
     * the control declares no limit.
     */
    static String fitToLimit(String value, DiscoveredField field) {
        if (value == null || field == null || !field.hasMaxLength()) return value;
        int limit = field.maxLength();
        if (value.length() <= limit) return value;

        String clipped = value.substring(0, limit);
        int lastSpace = clipped.lastIndexOf(' ');
        // Only prefer the word boundary if it does not throw away most of the allowance — for a
        // very short limit, a hard cut retains more real content than backing up to the first space.
        if (lastSpace > limit / 2) {
            clipped = clipped.substring(0, lastSpace);
        }
        return clipped.stripTrailing();
    }

    /** Placeholder tokens the engine swaps for real file paths at execution time. */
    public enum DocumentSlot {
        RESUME("__CAREERPILOT_RESUME__"),
        COVER_LETTER("__CAREERPILOT_COVER_LETTER__");

        private final String token;

        DocumentSlot(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }

        public static DocumentSlot fromToken(String value) {
            if (RESUME.token.equals(value)) return RESUME;
            if (COVER_LETTER.token.equals(value)) return COVER_LETTER;
            return null;
        }
    }
}
