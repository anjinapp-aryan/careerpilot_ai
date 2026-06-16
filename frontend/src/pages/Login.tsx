import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api } from '@/lib/api';
import { useAuthStore } from '@/lib/auth';

export default function Login() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const login = useAuthStore((s) => s.login);
  const nav = useNavigate();

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setErr(null);
    setLoading(true);
    try {
      const { data } = await api.post('/api/auth/login', { email, password });
      login(data.accessToken, {
        userId: data.userId,
        orgId: data.orgId,
        email: data.email,
        role: data.role,
        fullName: data.fullName,
      });
      nav('/');
    } catch (e: any) {
      setErr(e?.response?.data?.message || 'Login failed');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="min-h-screen grid place-items-center">
      <form onSubmit={onSubmit} className="bg-white p-8 rounded-xl shadow w-[380px] space-y-4">
        <h1 className="text-2xl font-semibold">Sign in to CareerPilot</h1>
        <input className="w-full border rounded-md p-2" type="email" placeholder="Email"
               value={email} onChange={(e) => setEmail(e.target.value)} required />
        <input className="w-full border rounded-md p-2" type="password" placeholder="Password"
               value={password} onChange={(e) => setPassword(e.target.value)} required />
        {err && <div className="text-rose-600 text-sm">{err}</div>}
        <button className="w-full bg-brand-600 text-white rounded-md py-2 disabled:opacity-60"
                disabled={loading}>
          {loading ? 'Signing in…' : 'Sign in'}
        </button>
        <div className="text-sm text-slate-600">
          No account? <Link className="text-brand-600" to="/register">Create one</Link>
        </div>
      </form>
    </div>
  );
}
