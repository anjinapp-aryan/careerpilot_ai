import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api } from '@/lib/api';
import { useAuthStore } from '@/lib/auth';

export default function Register() {
  const [form, setForm] = useState({ organizationName: '', fullName: '', email: '', password: '' });
  const [err, setErr] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const login = useAuthStore((s) => s.login);
  const nav = useNavigate();

  function on(k: keyof typeof form) {
    return (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [k]: e.target.value });
  }

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setErr(null);
    setLoading(true);
    try {
      const { data } = await api.post('/api/auth/register', form);
      login(data.accessToken, {
        userId: data.userId,
        orgId: data.orgId,
        email: data.email,
        role: data.role,
        fullName: data.fullName,
      });
      nav('/');
    } catch (e: any) {
      setErr(e?.response?.data?.message || 'Registration failed');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="min-h-screen grid place-items-center">
      <form onSubmit={onSubmit} className="bg-white p-8 rounded-xl shadow w-[420px] space-y-3">
        <h1 className="text-2xl font-semibold">Create your CareerPilot account</h1>
        <input className="w-full border rounded-md p-2" placeholder="Organization name"
               value={form.organizationName} onChange={on('organizationName')} required />
        <input className="w-full border rounded-md p-2" placeholder="Full name"
               value={form.fullName} onChange={on('fullName')} required />
        <input className="w-full border rounded-md p-2" type="email" placeholder="Email"
               value={form.email} onChange={on('email')} required />
        <input className="w-full border rounded-md p-2" type="password" placeholder="Password (min 8)"
               value={form.password} onChange={on('password')} required minLength={8} />
        {err && <div className="text-rose-600 text-sm">{err}</div>}
        <button className="w-full bg-brand-600 text-white rounded-md py-2 disabled:opacity-60"
                disabled={loading}>
          {loading ? 'Creating…' : 'Create account'}
        </button>
        <div className="text-sm text-slate-600">
          Already have one? <Link className="text-brand-600" to="/login">Sign in</Link>
        </div>
      </form>
    </div>
  );
}
