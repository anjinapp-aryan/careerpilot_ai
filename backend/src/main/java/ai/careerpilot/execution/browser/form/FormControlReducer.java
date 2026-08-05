package ai.careerpilot.execution.browser.form;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Phase B — collapses the raw control list from {@link FormDiscoveryScript} into one entry per
 * logical field, and reports exactly what it removed.
 *
 * <p><b>Why this is Java and not more JavaScript.</b> The suppression rule below is the single most
 * dangerous piece of logic in discovery: too loose and it deletes a real required field, which would
 * silently produce an incomplete application under a candidate's real name. Rules with that
 * consequence have to be unit-testable, and this package's existing rule is that everything
 * downstream of {@code parse} is pure Java precisely so it can be. Every framework case — React
 * Select, Material UI, Ant Design, HeadlessUI, Radix — is therefore a plain list of records in a
 * test, with no browser involved.
 *
 * <p>Pure, stateless, deterministic and thread-safe — the same discipline as
 * {@code VerificationAdjudicator}, {@code RetryPolicyService} and {@code AutomationConfidence}.
 *
 * <h2>The phantom problem</h2>
 * A component library renders one logical field as several DOM controls. React Select emits a
 * visible {@code role="combobox"} input carrying the real question, plus an invisible helper input
 * with no id, no name, and the placeholder text {@code "Select..."} as its only label. Phase A
 * measured the cost of counting both: on a live Greenhouse posting, four helper inputs inflated
 * required-control count from a true 7 to a reported 11 and contributed four false blockers, which
 * alone held automation confidence at 55.
 *
 * <h2>The suppression rule</h2>
 * A control is dropped only when <b>every</b> one of these holds:
 * <ol>
 *   <li>it shares a {@link DiscoveredField#widgetKey()} with at least one other control;</li>
 *   <li>that group contains a <em>substantive</em> control — one with an id or name, visible, and
 *       carrying a real label;</li>
 *   <li>it is itself not visible;</li>
 *   <li>it has neither an id nor a name;</li>
 *   <li>its label is absent or is a bare placeholder token.</li>
 * </ol>
 * Conditions 3–5 alone already mean the control is not fillable and cannot be identified, so
 * dropping it can never remove a field the engine could have filled. Conditions 1–2 are what
 * guarantee the real control survives: a helper is only ever removed when its own widget still has
 * a genuine representative in the list. A lone hidden control with no siblings is kept, because
 * with nothing to collapse onto, removing it would destroy the only evidence it exists.
 */
public final class FormControlReducer {

    private FormControlReducer() {
    }

    /**
     * Text a widget shows when it holds no value. Anchored, so a real question that merely contains
     * the word "select" ("Select the office closest to you") is never treated as a placeholder.
     */
    private static final Pattern PLACEHOLDER_LABEL = Pattern.compile(
            "^\\s*(select|choose|search|type|pick|please\\s+select|select\\s+an?\\s+option|none|-+)"
                    + "\\s*[.\u2026]*\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** The reduced field list plus the account of what reduction did to it. */
    public record Reduction(List<DiscoveredField> fields, DiscoveryDiagnostics diagnostics) {

        public Reduction {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }

    /**
     * Reduce a raw discovery result. Never throws, never returns null, and preserves the original
     * ordering of whatever survives — downstream code positionally aligns classifications against
     * this list, so a reordering here would silently mislabel every field.
     */
    public static Reduction reduce(List<DiscoveredField> raw, DiscoveryDiagnostics.RawCounters counters) {
        DiscoveryDiagnostics.RawCounters c =
                counters == null ? DiscoveryDiagnostics.RawCounters.unknown() : counters;
        if (raw == null || raw.isEmpty()) {
            return new Reduction(List.of(), new DiscoveryDiagnostics(
                    c.rawCandidates(), 0, 0, 0, 0, 0, 0, 0, c.rootsTraversed(),
                    c.shadowRootsFound(), c.sameOriginFrames(), c.crossOriginFrames(), 0L,
                    notesFor(c, 0, 0)));
        }

        // ── Pass 1: exact duplicates. Shadow-root and frame traversal walk overlapping trees, and
        // an element reachable from two roots would otherwise be emitted twice. ──
        List<DiscoveredField> deduped = new ArrayList<>(raw.size());
        Set<String> seen = new HashSet<>();
        int duplicates = 0;
        for (DiscoveredField f : raw) {
            if (f == null) continue;
            // Frame path participates in identity: the same selector in two frames is two controls.
            String identity = f.framePath() + "\u0000" + f.selector();
            if (!seen.add(identity)) {
                duplicates++;
                continue;
            }
            deduped.add(f);
        }

        // ── Pass 2: which widget groups have a substantive control to collapse onto, and what
        // those controls are called. The label set powers the duplicate-identity rule below. ──
        Map<String, Boolean> groupHasSubstantive = new LinkedHashMap<>();
        Map<String, Integer> groupSize = new LinkedHashMap<>();
        Map<String, Set<String>> groupLabels = new LinkedHashMap<>();
        for (DiscoveredField f : deduped) {
            String key = groupKey(f);
            if (key == null) continue;
            groupSize.merge(key, 1, Integer::sum);
            boolean substantive = isSubstantive(f);
            groupHasSubstantive.merge(key, substantive, Boolean::logicalOr);
            if (substantive) {
                groupLabels.computeIfAbsent(key, k -> new HashSet<>()).add(normalise(f.label()));
            }
        }

        // ── Pass 3: drop phantoms. ──
        List<DiscoveredField> kept = new ArrayList<>(deduped.size());
        int phantoms = 0;
        for (DiscoveredField f : deduped) {
            if (isPhantom(f, groupHasSubstantive, groupSize, groupLabels)) {
                phantoms++;
                continue;
            }
            kept.add(f);
        }

        int hidden = 0;
        int inFrame = 0;
        int inShadow = 0;
        int unlabelled = 0;
        for (DiscoveredField f : kept) {
            if (f.hidden()) hidden++;
            if (f.inFrame()) inFrame++;
            if (f.shadowDepth() > 0) inShadow++;
            if (!hasRealLabel(f)) unlabelled++;
        }

        DiscoveryDiagnostics diagnostics = new DiscoveryDiagnostics(
                Math.max(c.rawCandidates(), raw.size()), kept.size(), duplicates, phantoms,
                hidden, inFrame, inShadow, unlabelled, c.rootsTraversed(), c.shadowRootsFound(),
                c.sameOriginFrames(), c.crossOriginFrames(), 0L,
                notesFor(c, kept.size(), phantoms));

        return new Reduction(List.copyOf(kept), diagnostics);
    }

    /** A control participates in grouping only when the script gave it a widget key. */
    private static String groupKey(DiscoveredField f) {
        String key = f.widgetKey();
        if (key == null || key.isBlank()) return null;
        return f.framePath() + "\u0000" + key;
    }

    /**
     * A control worth keeping a widget's identity on: addressable, visible, and carrying a real
     * label. This is the representative a phantom is allowed to collapse onto.
     */
    private static boolean isSubstantive(DiscoveredField f) {
        return hasIdentifier(f) && !f.hidden() && hasRealLabel(f);
    }

    private static boolean isPhantom(DiscoveredField f, Map<String, Boolean> hasSubstantive,
                                     Map<String, Integer> sizes, Map<String, Set<String>> labels) {
        String key = groupKey(f);
        if (key == null) return false;                                   // no group — nothing to collapse onto
        if (sizes.getOrDefault(key, 0) < 2) return false;                // alone in its group
        if (!Boolean.TRUE.equals(hasSubstantive.get(key))) return false; // no survivor would remain
        if (!f.hidden()) return false;                                   // a user can see it: it is real
        if (hasIdentifier(f)) return false;                              // addressable: it is real
        if (!hasRealLabel(f)) return true;                               // unlabelled helper

        // Defence in depth. The discovery script refuses to guess a label for an invisible control,
        // so a helper normally arrives unlabelled and is caught above. A library that labels its
        // helper authoritatively — an aria-labelledby pointing at the same question element — would
        // otherwise slip through wearing the real control's identity. Two controls in one widget
        // claiming the same question is not two questions.
        Set<String> substantiveLabels = labels.get(key);
        return substantiveLabels != null && substantiveLabels.contains(normalise(f.label()));
    }

    /** Case- and whitespace-insensitive label identity, for the duplicate-identity rule. */
    private static String normalise(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean hasIdentifier(DiscoveredField f) {
        return notBlank(f.id()) || notBlank(f.name());
    }

    /**
     * A label a human would recognise as the question being asked. Placeholder text does not count —
     * that is the whole basis of phantom detection, and treating {@code "Select..."} as a label is
     * what made four real questions look like eight controls.
     */
    public static boolean hasRealLabel(DiscoveredField f) {
        for (String candidate : new String[]{f.label(), f.ariaLabel()}) {
            if (notBlank(candidate) && !isPlaceholderLabel(candidate)) return true;
        }
        return false;
    }

    /** Exposed for the classifier-adjacent tests that assert placeholder rejection directly. */
    public static boolean isPlaceholderLabel(String text) {
        if (text == null || text.isBlank()) return true;
        String cleaned = text.replace("*", "").replace(":", "").trim();
        return PLACEHOLDER_LABEL.matcher(cleaned).matches();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static List<String> notesFor(DiscoveryDiagnostics.RawCounters c, int kept, int phantoms) {
        List<String> notes = new ArrayList<>();
        if (c.crossOriginFrames() > 0) {
            notes.add(c.crossOriginFrames() + " cross-origin iframe(s) could not be inspected — "
                    + "this is a browser security boundary, not a discovery failure. Any form inside "
                    + "one is absent from these results.");
        }
        if (kept == 0 && c.sameOriginFrames() + c.crossOriginFrames() > 0) {
            notes.add("No controls discovered while iframes are present — the form is most likely "
                    + "inside a frame rather than the top document.");
        }
        if (phantoms > 0) {
            notes.add(phantoms + " framework helper control(s) collapsed onto their real sibling "
                    + "(React Select / MUI / Ant Design style hidden inputs).");
        }
        if (c.shadowRootsFound() > 0) {
            notes.add(c.shadowRootsFound() + " open shadow root(s) traversed.");
        }
        return List.copyOf(notes);
    }
}
