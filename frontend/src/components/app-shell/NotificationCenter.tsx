import { Bell, BellOff } from 'lucide-react';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Tooltip } from '@/components/ui/tooltip';

/**
 * Notification center. The backend has no notifications endpoint yet
 * (see `audit_logs` / `usage_records` are provisioned-but-unused), so this
 * renders an honest empty state and is ready to wire to a feed later.
 */
export function NotificationCenter() {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger>
        <Tooltip content="Notifications" side="bottom">
          <span className="relative flex h-9 w-9 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-muted hover:text-foreground">
            <Bell className="h-[18px] w-[18px]" />
            <span className="sr-only">Notifications</span>
          </span>
        </Tooltip>
      </DropdownMenuTrigger>
      <DropdownMenuContent className="w-80 p-0">
        <div className="flex items-center justify-between border-b border-border px-4 py-3">
          <p className="text-sm font-semibold">Notifications</p>
        </div>
        <div className="flex flex-col items-center justify-center gap-2 px-4 py-10 text-center">
          <div className="flex h-10 w-10 items-center justify-center rounded-full bg-muted text-muted-foreground">
            <BellOff className="h-5 w-5" />
          </div>
          <p className="text-sm font-medium text-foreground">You're all caught up</p>
          <p className="text-xs text-muted-foreground">
            Workflow updates and alerts will appear here.
          </p>
        </div>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
