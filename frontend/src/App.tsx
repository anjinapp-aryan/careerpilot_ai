import { Navigate, Route, Routes } from 'react-router-dom';
import { useAuthStore } from '@/lib/auth';
import Login from '@/pages/Login';
import Register from '@/pages/Register';
import Dashboard from '@/pages/Dashboard';
import Resumes from '@/pages/Resumes';
import ResumeOptimization from '@/pages/ResumeOptimization';
import Jobs from '@/pages/Jobs';
import Applications from '@/pages/Applications';
import Workflow from '@/pages/Workflow';
import AdminDashboard from '@/pages/AdminDashboard';
import AppShell from '@/components/app-shell/AppShell';

const ADMIN_ROLES = new Set(['OWNER', 'ADMIN']);

function Private({ children }: { children: JSX.Element }) {
  const token = useAuthStore((s) => s.token);
  return token ? children : <Navigate to="/login" replace />;
}

/** Frontend-side defense in depth — the backend independently 403s non-admins on every call. */
function AdminOnly({ children }: { children: JSX.Element }) {
  const role = useAuthStore((s) => s.user?.role);
  return role && ADMIN_ROLES.has(role) ? children : <Navigate to="/" replace />;
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/register" element={<Register />} />
      <Route
        path="/"
        element={
          <Private>
            <AppShell />
          </Private>
        }
      >
        <Route index element={<Dashboard />} />
        <Route path="resumes" element={<Resumes />} />
        <Route path="resumes/:id/optimize" element={<ResumeOptimization />} />
        <Route path="jobs" element={<Jobs />} />
        <Route path="applications" element={<Applications />} />
        <Route path="workflow" element={<Workflow />} />
        <Route
          path="admin"
          element={
            <AdminOnly>
              <AdminDashboard />
            </AdminOnly>
          }
        />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
