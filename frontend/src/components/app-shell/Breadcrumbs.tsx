import { Link, useLocation } from 'react-router-dom';
import { ChevronRight } from 'lucide-react';
import { labelForPath } from './nav';

/**
 * Route-derived breadcrumb trail: CareerPilot › <Current page>.
 * Kept intentionally shallow — the app is a flat set of top-level surfaces.
 */
export function Breadcrumbs() {
  const { pathname } = useLocation();
  const current = labelForPath(pathname);
  const isRoot = current === 'Dashboard' && pathname === '/';

  return (
    <nav aria-label="Breadcrumb" className="hidden items-center gap-1.5 text-sm md:flex">
      <Link
        to="/"
        className="font-medium text-muted-foreground transition-colors hover:text-foreground"
      >
        CareerPilot
      </Link>
      {!isRoot && (
        <>
          <ChevronRight className="h-3.5 w-3.5 text-muted-foreground/60" aria-hidden="true" />
          <span className="font-semibold text-foreground">{current}</span>
        </>
      )}
    </nav>
  );
}
