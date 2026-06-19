import { Menu, Search } from 'lucide-react';
import { Breadcrumbs } from './Breadcrumbs';
import { NotificationCenter } from './NotificationCenter';
import { ThemeToggle } from './ThemeToggle';
import { UserMenu } from './UserMenu';
import { useCommandPalette } from '@/hooks/useCommandPalette';
import { useSidebar } from '@/hooks/useSidebar';
import { Kbd } from '@/components/ui/kbd';
import { Separator } from '@/components/ui/separator';

/**
 * Sticky application top bar: mobile menu trigger, breadcrumbs, a global
 * command-palette search affordance, and the action cluster
 * (notifications, theme, user). Sits above the scrollable content.
 */
export function TopBar() {
  const openPalette = useCommandPalette((s) => s.setOpen);
  const openMobile = useSidebar((s) => s.setMobileOpen);

  return (
    <header className="sticky top-0 z-30 flex h-16 items-center gap-3 border-b border-border bg-background/80 px-4 backdrop-blur-md md:px-6">
      <button
        type="button"
        onClick={() => openMobile(true)}
        aria-label="Open navigation"
        className="flex h-9 w-9 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-muted hover:text-foreground lg:hidden"
      >
        <Menu className="h-5 w-5" />
      </button>

      <Breadcrumbs />

      <div className="flex flex-1 items-center justify-end gap-2">
        {/* Global search → command palette */}
        <button
          type="button"
          onClick={() => openPalette(true)}
          className="group flex h-9 items-center gap-2 rounded-lg border border-border bg-card pl-2.5 pr-2 text-sm text-muted-foreground transition-colors hover:border-primary/40 hover:text-foreground md:w-72"
          aria-label="Search"
        >
          <Search className="h-4 w-4 shrink-0" />
          <span className="hidden flex-1 text-left md:block">Search or jump to…</span>
          <Kbd className="hidden md:inline-flex">⌘K</Kbd>
        </button>

        <Separator orientation="vertical" className="mx-1 hidden h-6 md:block" />

        <NotificationCenter />
        <ThemeToggle />

        <Separator orientation="vertical" className="mx-1 hidden h-6 sm:block" />

        <UserMenu />
      </div>
    </header>
  );
}
