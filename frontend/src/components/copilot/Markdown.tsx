import { Fragment, type ReactNode } from 'react';

/**
 * Minimal, dependency-free markdown renderer for Copilot responses. Handles the
 * subset the model reliably emits: headings, bullet/numbered lists, bold,
 * inline code, and paragraphs. Content is rendered as text nodes (never
 * dangerouslySetInnerHTML), so it is XSS-safe.
 */
export function Markdown({ content }: { content: string }) {
  const lines = content.replace(/\r/g, '').split('\n');
  const blocks: ReactNode[] = [];
  let list: { ordered: boolean; items: string[] } | null = null;

  const flushList = (key: string) => {
    if (!list) return;
    const items = list.items.map((it, i) => <li key={i}>{inline(it)}</li>);
    blocks.push(
      list.ordered ? (
        <ol key={key} className="my-1.5 ml-4 list-decimal space-y-1">{items}</ol>
      ) : (
        <ul key={key} className="my-1.5 ml-4 list-disc space-y-1">{items}</ul>
      ),
    );
    list = null;
  };

  lines.forEach((raw, idx) => {
    const line = raw.trimEnd();
    const bullet = line.match(/^\s*[-*]\s+(.*)$/);
    const ordered = line.match(/^\s*\d+\.\s+(.*)$/);
    const heading = line.match(/^(#{1,4})\s+(.*)$/);

    if (bullet) {
      if (!list || list.ordered) flushList(`l${idx}`);
      list ??= { ordered: false, items: [] };
      list.items.push(bullet[1]);
      return;
    }
    if (ordered) {
      if (!list || !list.ordered) flushList(`l${idx}`);
      list ??= { ordered: true, items: [] };
      list.items.push(ordered[1]);
      return;
    }
    flushList(`l${idx}`);

    if (heading) {
      blocks.push(
        <p key={idx} className="mt-2 mb-1 text-sm font-semibold text-foreground">
          {inline(heading[2])}
        </p>,
      );
    } else if (line.trim() === '') {
      // skip blank lines (spacing handled by margins)
    } else {
      blocks.push(
        <p key={idx} className="leading-relaxed">
          {inline(line)}
        </p>,
      );
    }
  });
  flushList('last');

  return <div className="space-y-1 text-sm text-foreground">{blocks}</div>;
}

/** Inline formatting: **bold** and `code`. */
function inline(text: string): ReactNode {
  const parts = text.split(/(\*\*[^*]+\*\*|`[^`]+`)/g).filter(Boolean);
  return parts.map((part, i) => {
    if (part.startsWith('**') && part.endsWith('**')) {
      return <strong key={i} className="font-semibold">{part.slice(2, -2)}</strong>;
    }
    if (part.startsWith('`') && part.endsWith('`')) {
      return (
        <code key={i} className="rounded bg-muted px-1 py-0.5 font-mono text-[0.8em]">
          {part.slice(1, -1)}
        </code>
      );
    }
    return <Fragment key={i}>{part}</Fragment>;
  });
}
