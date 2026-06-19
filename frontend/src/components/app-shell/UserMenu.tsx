import { ChevronDown, LogOut, Settings, User as UserIcon, CreditCard } from 'lucide-react';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Avatar } from '@/components/ui/avatar';
import { Badge } from '@/components/ui/badge';
import { useAuthStore } from '@/lib/auth';

export function UserMenu() {
  const { user, logout } = useAuthStore();
  const name = user?.fullName || user?.email || 'Account';

  return (
    <DropdownMenu>
      <DropdownMenuTrigger className="gap-2 rounded-lg p-1 pr-2 transition-colors hover:bg-muted">
        <Avatar name={name} size="sm" />
        <span className="hidden max-w-[10rem] flex-col items-start leading-tight sm:flex">
          <span className="truncate text-sm font-medium text-foreground">{name}</span>
          {user?.role && (
            <span className="truncate text-[11px] capitalize text-muted-foreground">
              {user.role.toLowerCase()}
            </span>
          )}
        </span>
        <ChevronDown className="h-4 w-4 text-muted-foreground" />
      </DropdownMenuTrigger>
      <DropdownMenuContent className="w-64">
        <div className="flex items-center gap-3 px-2.5 py-2">
          <Avatar name={name} size="md" />
          <div className="min-w-0">
            <p className="truncate text-sm font-semibold text-foreground">{name}</p>
            <p className="truncate text-xs text-muted-foreground">{user?.email}</p>
          </div>
        </div>
        {user?.role && (
          <div className="px-2.5 pb-2">
            <Badge tone="primary" className="capitalize">
              {user.role.toLowerCase()} plan
            </Badge>
          </div>
        )}
        <DropdownMenuSeparator />
        <DropdownMenuItem>
          <UserIcon className="h-4 w-4 text-muted-foreground" /> Profile
        </DropdownMenuItem>
        <DropdownMenuItem>
          <Settings className="h-4 w-4 text-muted-foreground" /> Settings
        </DropdownMenuItem>
        <DropdownMenuItem>
          <CreditCard className="h-4 w-4 text-muted-foreground" /> Billing
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem tone="danger" onSelect={logout}>
          <LogOut className="h-4 w-4" /> Sign out
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
