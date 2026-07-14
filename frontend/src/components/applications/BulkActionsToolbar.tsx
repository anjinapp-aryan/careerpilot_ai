import { motion } from 'framer-motion';
import { Archive, Download, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import type { ApplicationBulkAction } from '@/types/workflow';

/**
 * Appears when >= 1 card is checkbox-selected; calls `POST /api/applications/bulk`. Status/notes/
 * next-action/resume-reassign bulk actions live on the drawer-triggered flows; this toolbar exposes
 * the two most common one-click bulk ops (archive, export) plus a status-move shortcut, matching the
 * plan's "no bulk AI regeneration" constraint (RESUME action is never offered from bulk UI here).
 */
export function BulkActionsToolbar({
  count,
  onArchive,
  onExport,
  onClear,
  pending,
}: {
  count: number;
  onArchive: () => void;
  onExport: () => void;
  onClear: () => void;
  pending?: ApplicationBulkAction | null;
}) {
  if (count === 0) return null;

  return (
    <motion.div
      initial={{ opacity: 0, y: -8 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0, y: -8 }}
      className="sticky top-0 z-20 flex items-center justify-between gap-3 rounded-xl border border-primary/30 bg-primary/5 px-4 py-2.5"
    >
      <span className="text-sm font-medium text-foreground">{count} selected</span>
      <div className="flex items-center gap-2">
        <Button size="sm" variant="outline" onClick={onArchive} loading={pending === 'ARCHIVE'}>
          <Archive className="h-3.5 w-3.5" /> Archive
        </Button>
        <Button size="sm" variant="outline" onClick={onExport} loading={pending === 'EXPORT'}>
          <Download className="h-3.5 w-3.5" /> Export
        </Button>
        <Button size="sm" variant="ghost" onClick={onClear}>
          <X className="h-3.5 w-3.5" /> Clear
        </Button>
      </div>
    </motion.div>
  );
}
