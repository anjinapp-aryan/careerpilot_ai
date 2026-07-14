import { ChevronLeft, ChevronRight } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/cn';

export interface PaginationProps {
  page: number;
  totalPages: number;
  totalElements: number;
  size: number;
  onPageChange: (page: number) => void;
  onSizeChange?: (size: number) => void;
  sizeOptions?: number[];
  className?: string;
}

function pageNumbers(page: number, totalPages: number): number[] {
  const start = Math.max(0, Math.min(page - 2, totalPages - 5));
  const end = Math.min(totalPages, start + 5);
  return Array.from({ length: Math.max(0, end - start) }, (_, i) => start + i);
}

export function Pagination({
  page,
  totalPages,
  totalElements,
  size,
  onPageChange,
  onSizeChange,
  sizeOptions = [20, 50, 100],
  className,
}: PaginationProps) {
  if (totalElements === 0) return null;

  const from = page * size + 1;
  const to = Math.min((page + 1) * size, totalElements);

  return (
    <div className={cn('flex flex-wrap items-center justify-between gap-3 pt-2', className)}>
      <p className="text-xs text-muted-foreground">
        Showing {from.toLocaleString()}–{to.toLocaleString()} of {totalElements.toLocaleString()}
      </p>
      <div className="flex items-center gap-2">
        {onSizeChange && (
          <select
            value={size}
            onChange={(e) => onSizeChange(Number(e.target.value))}
            className="h-8 rounded-lg border border-border bg-card px-2 text-xs text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            {sizeOptions.map((s) => (
              <option key={s} value={s}>
                {s} / page
              </option>
            ))}
          </select>
        )}
        <Button
          size="sm"
          variant="outline"
          disabled={page <= 0}
          onClick={() => onPageChange(page - 1)}
        >
          <ChevronLeft className="h-3.5 w-3.5" /> Previous
        </Button>
        <div className="flex items-center gap-1">
          {pageNumbers(page, totalPages).map((p) => (
            <button
              key={p}
              onClick={() => onPageChange(p)}
              className={cn(
                'h-8 min-w-8 rounded-lg px-2 text-xs font-medium transition-colors',
                p === page
                  ? 'bg-primary text-primary-foreground'
                  : 'text-muted-foreground hover:bg-muted hover:text-foreground',
              )}
            >
              {p + 1}
            </button>
          ))}
        </div>
        <Button
          size="sm"
          variant="outline"
          disabled={page >= totalPages - 1}
          onClick={() => onPageChange(page + 1)}
        >
          Next <ChevronRight className="h-3.5 w-3.5" />
        </Button>
      </div>
    </div>
  );
}
