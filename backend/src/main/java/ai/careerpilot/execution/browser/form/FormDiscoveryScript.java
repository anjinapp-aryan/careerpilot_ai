package ai.careerpilot.execution.browser.form;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Phase 12C — the browser-side half of field discovery, plus the parsing of its result back into
 * {@link DiscoveredField} records.
 *
 * <p><b>Why discovery runs in the page rather than over fetched HTML:</b> this codebase has no HTML
 * parser dependency, and adding one would still be wrong here. Real ATS forms are
 * JavaScript-rendered, and the signals that actually identify a field — the computed label, whether
 * a control is visible, whether it is currently disabled, what options a select really holds — only
 * exist in a live DOM. Parsing served HTML would see an empty {@code <div id="app">}.
 *
 * <p>The script returns plain JSON-ish maps. Everything downstream of {@link #parse} is pure Java,
 * which is what keeps classification, resolution and planning testable without a browser.
 *
 * <p>The script is deliberately read-only: it queries, it never clicks, focuses, or sets a value.
 */
public final class FormDiscoveryScript {

    private FormDiscoveryScript() {
    }

    /**
     * Label resolution follows the accessibility precedence real ATSes rely on, in order:
     * {@code aria-labelledby} → {@code aria-label} → {@code <label for>} → wrapping {@code <label>}
     * → preceding sibling text. A single strategy covers roughly one vendor; this covers all of
     * them without vendor-specific code.
     */
    public static final String DISCOVER_FIELDS = """
            () => {
              // Widget vocabulary. Native controls plus the ARIA roles that component libraries
              // (React Select, MUI, Ant Design, HeadlessUI, Radix) use when they render a div
              // instead of a native control. Without the roles, a React Select is invisible to
              // discovery except for the hidden helper input it leaves behind.
              const CAND = 'input, textarea, select, [contenteditable="true"],'
                + ' [role="combobox"], [role="textbox"], [role="listbox"],'
                + ' [role="checkbox"], [role="radio"], [role="switch"]';

              const vis = el => {
                if (el.getAttribute && el.getAttribute('aria-hidden') === 'true') return false;
                const win = (el.ownerDocument && el.ownerDocument.defaultView) || window;
                const s = win.getComputedStyle(el);
                if (!s || s.display === 'none' || s.visibility === 'hidden' || s.opacity === '0') return false;
                const r = el.getBoundingClientRect();
                return r.width > 0 && r.height > 0;
              };
              const txt = el => (el && el.textContent ? el.textContent.replace(/\\s+/g, ' ').trim() : '');
              const rootOf = el => {
                const r = el.getRootNode ? el.getRootNode() : el.ownerDocument;
                return r || el.ownerDocument;
              };
              // A placeholder is what a widget shows when it holds no value. It is never the
              // question the user is answering, so it must not become a label.
              const PLACEHOLDER = /^\\s*(select|choose|search|type|pick|please\\s+select|select\\s+an?\\s+option|none|-+)\\s*[.…]*\\s*$/i;
              const isPlaceholderText = t => !t || PLACEHOLDER.test(t.replace(/[*:]/g, '').trim());

              // `visible` gates the *heuristic* half of label resolution. Authoritative sources
              // (aria-labelledby, aria-label, label[for], wrapping label, legend) are always used;
              // the proximity and heading fallbacks are guesses, and letting an invisible control
              // guess a label is precisely how a framework helper input acquires the identity of
              // the real question sitting next to it — which then makes it indistinguishable from
              // that question and defeats phantom suppression downstream.
              const labelFor = (el, visible) => {
                const root = rootOf(el);
                const find = id => (root.getElementById ? root.getElementById(id)
                                                        : root.querySelector('#' + CSS.escape(id)));
                const by = el.getAttribute('aria-labelledby');
                if (by) {
                  const parts = by.split(/\\s+/).map(id => txt(find(id))).filter(Boolean);
                  if (parts.length) return parts.join(' ');
                }
                const aria = el.getAttribute('aria-label');
                if (aria && aria.trim()) return aria.trim();
                if (el.id) {
                  const l = root.querySelector('label[for="' + CSS.escape(el.id) + '"]');
                  if (l && txt(l)) return txt(l);
                }
                const wrap = el.closest('label');
                if (wrap && txt(wrap)) return txt(wrap);
                // Fieldset legend, walking the whole fieldset hierarchy rather than one level, so a
                // control nested two groups deep still inherits the question it belongs to.
                for (let fs = el.closest('fieldset'); fs; fs = fs.parentElement && fs.parentElement.closest('fieldset')) {
                  const lg = fs.querySelector('legend');
                  if (lg && txt(lg)) return txt(lg);
                }
                const desc = el.getAttribute('aria-describedby');
                if (desc) {
                  const parts = desc.split(/\\s+/).map(id => txt(find(id))).filter(Boolean);
                  if (parts.length && !isPlaceholderText(parts.join(' '))) return parts.join(' ');
                }
                // Everything past this point is proximity guesswork — authoritative sources are
                // exhausted. An invisible control stops here with no label, which is the honest
                // answer: nothing on the page labels it.
                if (!visible) return '';
                // Nearest visible preceding text, searched up through ancestors rather than only
                // among immediate siblings — component libraries wrap the control in two or three
                // divs, which put the label out of sibling reach.
                for (let node = el, hops = 0; node && hops < 4; node = node.parentElement, hops++) {
                  for (let p = node.previousElementSibling, i = 0; p && i < 3; p = p.previousElementSibling, i++) {
                    const t = txt(p);
                    if (t && t.length < 300 && !isPlaceholderText(t) && vis(p)) return t;
                  }
                }
                // Heading association: the closest section/group heading above the control.
                const grp = el.closest('[role="group"], section, .form-group, .field');
                if (grp) {
                  const h = grp.querySelector('h1, h2, h3, h4, h5, h6, legend, label');
                  if (h && txt(h) && !isPlaceholderText(txt(h))) return txt(h);
                }
                return '';
              };

              const sel = el => {
                if (el.id) return '#' + CSS.escape(el.id);
                const n = el.getAttribute('name');
                if (n) return el.tagName.toLowerCase() + '[name="' + n.replace(/"/g, '\\\\"') + '"]';
                const root = rootOf(el);
                const all = Array.from(root.querySelectorAll(el.tagName.toLowerCase()));
                return el.tagName.toLowerCase() + ':nth-of-type(' + (all.indexOf(el) + 1) + ')';
              };
              const dataAttrs = el => Array.from(el.attributes)
                .filter(a => a.name.startsWith('data-'))
                .map(a => a.name.substring(5) + ' ' + a.value)
                .join(' ');
              const xpath = el => {
                const parts = [];
                for (let n = el; n && n.nodeType === 1; n = n.parentNode) {
                  let i = 1;
                  for (let s = n.previousSibling; s; s = s.previousSibling) {
                    if (s.nodeType === 1 && s.nodeName === n.nodeName) i++;
                  }
                  parts.unshift(n.nodeName.toLowerCase() + '[' + i + ']');
                  if (parts.length > 40) break;
                }
                return '/' + parts.join('/');
              };

              // The grouping key that lets Java collapse a widget's helper inputs onto the one real
              // control. The nearest ancestor holding more than one candidate IS the widget
              // container: a plain form row wrapping a single input never qualifies, so unrelated
              // fields are never grouped together.
              const widgetKeyOf = el => {
                let anc = el.parentElement;
                for (let i = 0; anc && i < 6; i++, anc = anc.parentElement) {
                  let n = 0;
                  try { n = anc.querySelectorAll(CAND).length; } catch (e) { n = 0; }
                  if (n > 1) return xpath(anc);
                }
                return '';
              };

              // ── Root traversal: top document, then open shadow roots, then same-origin frames ──
              const roots = [];
              const seenRoots = new Set();
              let shadowRootsFound = 0;
              let sameOriginFrames = 0;
              let crossOriginFrames = 0;

              const collect = (root, framePath, shadowDepth) => {
                if (!root || seenRoots.has(root) || roots.length > 60) return;
                seenRoots.add(root);
                roots.push({ root: root, framePath: framePath, shadowDepth: shadowDepth });

                let all = [];
                try { all = Array.from(root.querySelectorAll('*')); } catch (e) { all = []; }
                for (const el of all) {
                  // Open shadow roots only. A closed root is inaccessible by design and we do not
                  // attempt to reach into one.
                  if (el.shadowRoot) {
                    shadowRootsFound++;
                    collect(el.shadowRoot, framePath, shadowDepth + 1);
                  }
                }

                let frames = [];
                try { frames = Array.from(root.querySelectorAll('iframe, frame')); } catch (e) { frames = []; }
                frames.forEach((f, i) => {
                  let doc = null;
                  // Cross-origin access throws by design. We never work around it — the count is
                  // reported so an empty form inside a cross-origin frame is diagnosable.
                  try { doc = f.contentDocument; } catch (e) { doc = null; }
                  if (!doc) { crossOriginFrames++; return; }
                  sameOriginFrames++;
                  const path = (framePath ? framePath + ' >> ' : '')
                    + (f.id ? '#' + f.id : 'iframe:nth-of-type(' + (i + 1) + ')');
                  collect(doc, path, shadowDepth);
                });
              };
              collect(document, '', 0);

              const out = [];
              const seenEls = new Set();
              const seenRadio = new Set();
              let rawCandidates = 0;

              for (const entry of roots) {
                let els = [];
                try { els = Array.from(entry.root.querySelectorAll(CAND)); } catch (e) { els = []; }
                for (const el of els) {
                  // One element is one control, however many selectors matched it.
                  if (seenEls.has(el)) continue;
                  seenEls.add(el);

                  const tag = el.tagName.toLowerCase();
                  const type = (el.getAttribute('type') || '').toLowerCase();
                  if (type === 'hidden' || type === 'submit' || type === 'button' || type === 'reset') continue;
                  rawCandidates++;

                  const role = el.getAttribute('role') || '';
                  const visible = vis(el);
                  let options = [];
                  if (type === 'radio' || role === 'radio') {
                    const name = el.getAttribute('name') || '';
                    if (name) {
                      if (seenRadio.has(name)) continue;
                      seenRadio.add(name);
                      let peers = [];
                      try {
                        peers = Array.from(entry.root.querySelectorAll(
                          'input[type="radio"][name="' + CSS.escape(name) + '"]'));
                      } catch (e) { peers = []; }
                      options = peers.map(r => labelFor(r, vis(r)) || r.value).filter(Boolean);
                    }
                  } else if (tag === 'select') {
                    options = Array.from(el.options).map(o => (o.label || o.text || o.value || '').trim())
                      .filter(v => v.length > 0);
                  } else if (role === 'combobox' || role === 'listbox') {
                    const owns = el.getAttribute('aria-controls') || el.getAttribute('aria-owns');
                    if (owns) {
                      const box = entry.root.getElementById ? entry.root.getElementById(owns) : null;
                      if (box) {
                        options = Array.from(box.querySelectorAll('[role="option"], li, option'))
                          .map(txt).filter(Boolean).slice(0, 60);
                      }
                    }
                  }

                  out.push({
                    selector: sel(el),
                    tag: tag,
                    type: type,
                    contentEditable: el.getAttribute('contenteditable') === 'true',
                    name: el.getAttribute('name') || '',
                    id: el.id || '',
                    label: labelFor(el, visible),
                    ariaLabel: el.getAttribute('aria-label') || '',
                    placeholder: el.getAttribute('placeholder') || '',
                    autocomplete: el.getAttribute('autocomplete') || '',
                    dataAttributes: dataAttrs(el),
                    required: el.required === true || el.getAttribute('aria-required') === 'true',
                    hidden: !visible,
                    disabled: el.disabled === true || el.getAttribute('aria-disabled') === 'true',
                    readOnly: el.readOnly === true,
                    maxLength: (typeof el.maxLength === 'number' && el.maxLength > 0) ? el.maxLength : -1,
                    role: role,
                    xpath: xpath(el),
                    widgetKey: widgetKeyOf(el),
                    framePath: entry.framePath,
                    shadowDepth: entry.shadowDepth,
                    options: options
                  });
                }
              }

              return {
                fields: out,
                diagnostics: {
                  rawCandidates: rawCandidates,
                  rootsTraversed: roots.length,
                  shadowRootsFound: shadowRootsFound,
                  sameOriginFrames: sameOriginFrames,
                  crossOriginFrames: crossOriginFrames
                }
              };
            }
            """;

    /**
     * Clickable controls, for {@link MultiStepFormNavigator}. Kept separate from field discovery so
     * a page that fails one still yields the other.
     */
    public static final String DISCOVER_BUTTONS = """
            () => {
              const txt = el => {
                const aria = el.getAttribute('aria-label');
                if (aria && aria.trim()) return aria.trim();
                const v = el.getAttribute('value');
                if (el.tagName.toLowerCase() === 'input' && v) return v.trim();
                return (el.textContent || '').replace(/\\s+/g, ' ').trim();
              };
              const vis = el => {
                const s = window.getComputedStyle(el);
                if (s.display === 'none' || s.visibility === 'hidden') return false;
                const r = el.getBoundingClientRect();
                return r.width > 0 && r.height > 0;
              };
              const sel = el => {
                if (el.id) return '#' + CSS.escape(el.id);
                const all = Array.from(document.querySelectorAll('button, input[type="submit"], [role="button"]'));
                return ':is(button, input[type="submit"], [role="button"]):nth-of-type(' + (all.indexOf(el) + 1) + ')';
              };
              return Array.from(document.querySelectorAll('button, input[type="submit"], input[type="button"], [role="button"]'))
                .filter(vis)
                .map(el => ({ selector: sel(el), label: txt(el), enabled: !el.disabled }))
                .filter(b => b.label.length > 0);
            }
            """;

    /**
     * Post-submit page state for {@link ValidationErrorDetector}. Error text is read from live
     * regions and {@code aria-invalid} controls only — never scraped from arbitrary red text, which
     * would false-positive on a page's own styling.
     */
    public static final String DISCOVER_VALIDATION_STATE = """
            () => {
              const txt = el => (el.textContent || '').replace(/\\s+/g, ' ').trim();
              const invalid = Array.from(document.querySelectorAll('[aria-invalid="true"]'))
                .map(el => el.id ? '#' + CSS.escape(el.id)
                     : (el.getAttribute('name') ? el.tagName.toLowerCase() + '[name="' + el.getAttribute('name') + '"]' : ''))
                .filter(s => s.length > 0);
              const messages = Array.from(document.querySelectorAll(
                  '[role="alert"], [aria-live="assertive"], [aria-live="polite"], .error, .field-error, .help-block, .invalid-feedback'))
                .map(txt).filter(t => t.length > 0 && t.length < 500);
              return { invalidFieldSelectors: invalid, errorMessages: messages };
            }
            """;

    /**
     * Phase 12C.5 — what kind of page this is. Read-only, and everything it reports is a property
     * that makes a form <em>hard to automate</em>: a framework that renders late, an iframe or
     * shadow root the discovery query cannot see into, a CAPTCHA, a consent overlay covering the
     * form.
     *
     * <p>Iframe and shadow-root counts matter specifically because {@link #DISCOVER_FIELDS} queries
     * the top-level document only. A form inside one is invisible to it, and a page reporting zero
     * discovered fields with a non-zero iframe count is a very different diagnosis from one that is
     * genuinely empty.
     *
     * <p>CAPTCHA is <b>detected and reported, never solved</b> — solving one would be circumventing
     * an access control the employer deliberately placed.
     */
    public static final String DISCOVER_ENVIRONMENT = """
            () => {
              const html = document.documentElement ? document.documentElement.outerHTML : '';
              const lower = html.toLowerCase();
              let framework = null;
              if (window.React || document.querySelector('[data-reactroot], #__next, [data-reactid]')) framework = 'React';
              else if (window.ng || document.querySelector('[ng-version], app-root')) framework = 'Angular';
              else if (window.__VUE__ || document.querySelector('[data-v-app], #__nuxt')) framework = 'Vue';
              else if (document.querySelector('[data-svelte-h]')) framework = 'Svelte';

              let shadowRoots = 0;
              document.querySelectorAll('*').forEach(el => { if (el.shadowRoot) shadowRoots++; });

              const captcha = /recaptcha|hcaptcha|cf-turnstile|g-recaptcha|captcha-delivery/.test(lower);
              const banner = !!document.querySelector(
                  '[class*="cookie" i], [class*="consent" i], [id*="cookie" i], [id*="consent" i]');

              let failed = 0;
              try {
                failed = performance.getEntriesByType('resource')
                  .filter(r => r.responseStatus && r.responseStatus >= 400).length;
              } catch (e) { failed = 0; }

              return {
                spaFramework: framework,
                iframeCount: document.querySelectorAll('iframe').length,
                shadowRootCount: shadowRoots,
                captchaDetected: captcha,
                cookieBannerDetected: banner,
                failedRequests: failed,
                title: document.title || ''
              };
            }
            """;

    /** Parse {@link #DISCOVER_ENVIRONMENT}. Missing keys degrade to a neutral value, never a throw. */
    public static ai.careerpilot.execution.browser.validation.ValidationReport.PageEnvironment
            parseEnvironment(Object scriptResult, int consoleErrorCount) {
        if (!(scriptResult instanceof Map<?, ?> map)) {
            return ai.careerpilot.execution.browser.validation.ValidationReport.PageEnvironment.unknown();
        }
        String framework = str(map, "spaFramework");
        return new ai.careerpilot.execution.browser.validation.ValidationReport.PageEnvironment(
                framework.isEmpty() || "null".equals(framework) ? null : framework,
                count(map, "iframeCount"),
                count(map, "shadowRootCount"),
                bool(map, "captchaDetected"),
                bool(map, "cookieBannerDetected"),
                Math.max(0, consoleErrorCount),
                count(map, "failedRequests"),
                str(map, "title"));
    }

    /**
     * Parse the script's return value. Tolerant by construction: a malformed or partial entry is
     * skipped rather than failing the whole discovery, because one unusual control on a 40-field
     * form must not cost us the other 39.
     */
    public static List<DiscoveredField> parse(Object scriptResult) {
        Object rowsValue = scriptResult;
        // Phase B changed the script's return shape from a bare array to {fields, diagnostics}.
        // Both are accepted: the map form is what the current script emits, and the list form keeps
        // every pre-Phase-B caller and fixture working unchanged.
        if (scriptResult instanceof Map<?, ?> envelope) {
            rowsValue = envelope.get("fields");
        }
        if (!(rowsValue instanceof List<?> rows)) return List.of();
        List<DiscoveredField> out = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) continue;
            try {
                String selector = str(map, "selector");
                if (selector.isEmpty()) continue;
                FieldControlType type = FieldControlType.from(
                        str(map, "tag"), str(map, "type"), bool(map, "contentEditable"));
                out.add(new DiscoveredField(
                        selector,
                        type,
                        str(map, "name"),
                        str(map, "id"),
                        str(map, "label"),
                        str(map, "ariaLabel"),
                        str(map, "placeholder"),
                        str(map, "autocomplete"),
                        str(map, "dataAttributes"),
                        bool(map, "required"),
                        bool(map, "hidden"),
                        bool(map, "disabled"),
                        bool(map, "readOnly"),
                        (int) num(map, "maxLength"),
                        str(map, "role"),
                        str(map, "xpath"),
                        strList(map.get("options")),
                        str(map, "widgetKey"),
                        str(map, "framePath"),
                        Math.max(0, (int) num(map, "shadowDepth"))));
            } catch (RuntimeException e) {
                // Skip this control only.
            }
        }
        return List.copyOf(out);
    }

    /**
     * Phase B — the traversal counters the script itself observed: how many raw candidates it saw
     * before any reduction, how many roots it walked, and how many shadow roots and frames it found.
     *
     * <p>These cannot be recomputed in Java, because by the time discovery returns, the DOM the
     * numbers describe is gone. A cross-origin frame in particular leaves no trace in the field
     * list at all — an empty result with a non-zero cross-origin count is a completely different
     * diagnosis from an empty page, and this is the only place that distinction survives.
     */
    public static DiscoveryDiagnostics.RawCounters parseRawCounters(Object scriptResult) {
        if (!(scriptResult instanceof Map<?, ?> envelope)) {
            return DiscoveryDiagnostics.RawCounters.unknown();
        }
        if (!(envelope.get("diagnostics") instanceof Map<?, ?> d)) {
            return DiscoveryDiagnostics.RawCounters.unknown();
        }
        return new DiscoveryDiagnostics.RawCounters(
                count(d, "rawCandidates"),
                count(d, "rootsTraversed"),
                count(d, "shadowRootsFound"),
                count(d, "sameOriginFrames"),
                count(d, "crossOriginFrames"));
    }

    /** Parse {@link #DISCOVER_BUTTONS} output. */
    public static List<MultiStepFormNavigator.Button> parseButtons(Object scriptResult) {
        if (!(scriptResult instanceof List<?> rows)) return List.of();
        List<MultiStepFormNavigator.Button> out = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) continue;
            String selector = str(map, "selector");
            String label = str(map, "label");
            if (selector.isEmpty() || label.isEmpty()) continue;
            out.add(new MultiStepFormNavigator.Button(selector, label, bool(map, "enabled")));
        }
        return List.copyOf(out);
    }

    /** Parse {@link #DISCOVER_VALIDATION_STATE} output. */
    public static ValidationErrorDetector.PostSubmitState parseValidationState(Object scriptResult, boolean urlChanged) {
        if (!(scriptResult instanceof Map<?, ?> map)) {
            return new ValidationErrorDetector.PostSubmitState(List.of(), List.of(), urlChanged);
        }
        return new ValidationErrorDetector.PostSubmitState(
                strList(map.get("invalidFieldSelectors")),
                strList(map.get("errorMessages")),
                urlChanged);
    }

    private static String str(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static boolean bool(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
    }

    /** A count, floored at 0 — {@link #num} signals "absent" with -1, which is not a valid count. */
    private static int count(Map<?, ?> map, String key) {
        return Math.max(0, (int) num(map, key));
    }

    private static double num(Map<?, ?> map, String key) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static List<String> strList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o == null) continue;
            String s = String.valueOf(o).trim();
            if (!s.isEmpty()) out.add(s);
        }
        return List.copyOf(out);
    }
}
