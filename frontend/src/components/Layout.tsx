import { NavLink, Outlet } from 'react-router-dom';
import { useAuthStore } from '@/lib/auth';

const items = [
  { to: '/', label: 'Dashboard' },
  { to: '/resumes', label: 'Resumes' },
  { to: '/jobs', label: 'Jobs' },
  { to: '/applications', label: 'Applications' },
  { to: '/workflow', label: 'AI Workflow' },
];

export default function Layout() {
  const { user, logout } = useAuthStore();
  return (
    <div className="min-h-full grid grid-cols-[240px_1fr]">
      <aside className="bg-slate-900 text-slate-100 p-4 flex flex-col">
        <div className="text-xl font-semibold mb-6">CareerPilot AI</div>
        <nav className="flex flex-col gap-1">
          {items.map((it) => (
            <NavLink
              key={it.to}
              to={it.to}
              end={it.to === '/'}
              className={({ isActive }) =>
                `px-3 py-2 rounded-md text-sm ${
                  isActive ? 'bg-brand-600' : 'hover:bg-slate-800'
                }`
              }
            >
              {it.label}
            </NavLink>
          ))}
        </nav>
        <div className="mt-auto text-xs opacity-80">
          <div className="mb-2">{user?.email}</div>
          <button onClick={logout} className="underline">
            Sign out
          </button>
        </div>
      </aside>
      <main className="p-8">
        <Outlet />
      </main>
    </div>
  );
}
