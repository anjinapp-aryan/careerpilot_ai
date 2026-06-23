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
import AppShell from '@/components/app-shell/AppShell';

function Private({ children }: { children: JSX.Element }) {
  const token = useAuthStore((s) => s.token);
  return token ? children : <Navigate to="/login" replace />;
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
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
