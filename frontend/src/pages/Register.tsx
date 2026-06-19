import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { AlertTriangle, Compass } from 'lucide-react';
import { api } from '@/lib/api';
import { useAuthStore } from '@/lib/auth';
import { AuthLayout } from '@/components/auth/AuthLayout';
import { Button } from '@/components/ui/button';
import { Input, Label } from '@/components/ui/input';

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
    <AuthLayout>
      <div className="mb-8 flex items-center gap-2.5 lg:hidden">
        <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-primary text-primary-foreground">
          <Compass className="h-5 w-5" />
        </span>
        <span className="text-lg font-semibold tracking-tight text-foreground">CareerPilot AI</span>
      </div>

      <h1 className="text-2xl font-semibold tracking-tight text-foreground">Create your account</h1>
      <p className="mt-1.5 text-sm text-muted-foreground">Start optimizing your career in minutes.</p>

      <form onSubmit={onSubmit} className="mt-8 space-y-4">
        <div>
          <Label htmlFor="org">Organization</Label>
          <Input id="org" placeholder="Acme Inc." value={form.organizationName} onChange={on('organizationName')} required />
        </div>
        <div>
          <Label htmlFor="name">Full name</Label>
          <Input id="name" placeholder="Jane Doe" value={form.fullName} onChange={on('fullName')} required />
        </div>
        <div>
          <Label htmlFor="email">Email</Label>
          <Input id="email" type="email" autoComplete="email" placeholder="you@company.com" value={form.email} onChange={on('email')} required />
        </div>
        <div>
          <Label htmlFor="password">Password</Label>
          <Input
            id="password"
            type="password"
            autoComplete="new-password"
            placeholder="At least 8 characters"
            value={form.password}
            onChange={on('password')}
            required
            minLength={8}
          />
        </div>

        {err && (
          <div className="flex items-center gap-2 rounded-lg border border-danger/30 bg-danger/10 px-3 py-2 text-sm text-danger">
            <AlertTriangle className="h-4 w-4 shrink-0" />
            {err}
          </div>
        )}

        <Button type="submit" className="w-full" size="lg" loading={loading}>
          {loading ? 'Creating…' : 'Create account'}
        </Button>
        <p className="text-center text-xs text-muted-foreground">
          By continuing you agree to our Terms & Privacy Policy.
        </p>
      </form>

      <p className="mt-6 text-center text-sm text-muted-foreground">
        Already have an account?{' '}
        <Link to="/login" className="font-semibold text-primary hover:underline">
          Sign in
        </Link>
      </p>
    </AuthLayout>
  );
}
