import { Sun, Moon, Monitor, Check } from 'lucide-react';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Tooltip } from '@/components/ui/tooltip';
import { useTheme, type ThemeMode } from '@/lib/theme';
import { cn } from '@/lib/cn';

const OPTIONS: { value: ThemeMode; label: string; icon: typeof Sun }[] = [
  { value: 'light', label: 'Light', icon: Sun },
  { value: 'dark', label: 'Dark', icon: Moon },
  { value: 'system', label: 'System', icon: Monitor },
];

export function ThemeToggle() {
  const mode = useTheme((s) => s.mode);
  const resolved = useTheme((s) => s.resolved);
  const setMode = useTheme((s) => s.setMode);

  return (
    <DropdownMenu>
      <DropdownMenuTrigger>
        <Tooltip content="Theme" side="bottom">
          <span className="flex h-9 w-9 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-muted hover:text-foreground">
            {resolved === 'dark' ? (
              <Moon className="h-[18px] w-[18px]" />
            ) : (
              <Sun className="h-[18px] w-[18px]" />
            )}
            <span className="sr-only">Toggle theme</span>
          </span>
        </Tooltip>
      </DropdownMenuTrigger>
      <DropdownMenuContent>
        <DropdownMenuLabel>Appearance</DropdownMenuLabel>
        <DropdownMenuSeparator />
        {OPTIONS.map((opt) => {
          const Icon = opt.icon;
          const active = mode === opt.value;
          return (
            <DropdownMenuItem key={opt.value} onSelect={() => setMode(opt.value)}>
              <Icon className="h-4 w-4 text-muted-foreground" />
              <span className="flex-1 text-left">{opt.label}</span>
              <Check
                className={cn('h-4 w-4 text-primary', active ? 'opacity-100' : 'opacity-0')}
              />
            </DropdownMenuItem>
          );
        })}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
