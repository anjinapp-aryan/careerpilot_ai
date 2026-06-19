import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { AlertTriangle, Compass } from 'lucide-react';
import { api } from '@/lib/api';
import { useAuthStore } from '@/lib/auth';
import { AuthLayout } from '@/components/auth/AuthLayout';
import { Button } from '@/components/ui/button';
import { Input, Label } from '@/components/ui/input';
import { useToast } from '@/components/ui/toast';

export default function Login() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const login = useAuthStore((s) => s.login);
  const sessionExpired = useAuthStore((s) => s.sessionExpired);
  const clearSessionExpired = useAuthStore((s) => s.clearSessionExpired);
  const { toast } = useToast();
  const nav = useNavigate();

  // Surfaced when the user was bounced here by a server-side 401 (expired/invalid
  // token) rather than an explicit logout. Shown once, then acknowledged.
  useEffect(() => {
    if (sessionExpired) {
      toast({
        variant: 'warning',
        title: 'Session expired',
        description: 'Please sign in again to continue.',
      });
      clearSessionExpired();
    }
  }, [sessionExpired, clearSessionExpired, toast]);

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
      setErr(e?.response?.data?.message || 'Invalid email or password');
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

      <h1 className="text-2xl font-semibold tracking-tight text-foreground">Welcome back</h1>
      <p className="mt-1.5 text-sm text-muted-foreground">Sign in to continue to your dashboard.</p>

      <form onSubmit={onSubmit} className="mt-8 space-y-4">
        <div>
          <Label htmlFor="email">Email</Label>
          <Input
            id="email"
            type="email"
            autoComplete="email"
            placeholder="you@company.com"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
          />
        </div>
        <div>
          <div className="flex items-center justify-between">
            <Label htmlFor="password">Password</Label>
            <span className="mb-1.5 text-xs font-medium text-primary">Forgot?</span>
          </div>
          <Input
            id="password"
            type="password"
            autoComplete="current-password"
            placeholder="••••••••"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </div>

        {err && (
          <div className="flex items-center gap-2 rounded-lg border border-danger/30 bg-danger/10 px-3 py-2 text-sm text-danger">
            <AlertTriangle className="h-4 w-4 shrink-0" />
            {err}
          </div>
        )}

        <Button type="submit" className="w-full" size="lg" loading={loading}>
          {loading ? 'Signing in…' : 'Sign in'}
        </Button>
      </form>

      <p className="mt-6 text-center text-sm text-muted-foreground">
        No account?{' '}
        <Link to="/register" className="font-semibold text-primary hover:underline">
          Create one
        </Link>
      </p>
    </AuthLayout>
  );
}
