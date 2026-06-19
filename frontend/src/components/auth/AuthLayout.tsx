import type { ReactNode } from 'react';
import { motion } from 'framer-motion';
import { Compass, BarChart3, ShieldCheck, Sparkles } from 'lucide-react';

const HIGHLIGHTS = [
  { icon: Sparkles, title: 'AI-powered workflows', body: 'Multi-agent pipeline optimizes your resume end to end.' },
  { icon: BarChart3, title: 'Career analytics', body: 'Track ATS scores, match rates, and interview funnels.' },
  { icon: ShieldCheck, title: 'Enterprise-grade', body: 'Secure, multi-tenant, and built for teams.' },
];

/** Split-screen auth shell: marketing panel + form column. */
export function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <div className="grid min-h-screen lg:grid-cols-2">
      {/* Brand panel */}
      <div className="relative hidden overflow-hidden bg-gradient-to-br from-primary via-primary to-secondary p-12 text-primary-foreground lg:flex lg:flex-col lg:justify-between">
        <div className="pointer-events-none absolute -right-20 -top-24 h-80 w-80 rounded-full bg-white/10 blur-3xl" />
        <div className="pointer-events-none absolute bottom-0 left-10 h-72 w-72 rounded-full bg-white/10 blur-3xl" />

        <div className="relative flex items-center gap-2.5">
          <span className="flex h-10 w-10 items-center justify-center rounded-xl bg-white/15">
            <Compass className="h-5 w-5" />
          </span>
          <span className="text-lg font-semibold tracking-tight">CareerPilot AI</span>
        </div>

        <div className="relative max-w-md">
          <h2 className="text-3xl font-semibold leading-tight tracking-tight">
            Your AI copilot for the entire career journey.
          </h2>
          <p className="mt-3 text-white/80">
            Optimize resumes, discover roles, ace interviews, and track every
            application — all in one intelligent platform.
          </p>
          <div className="mt-10 space-y-5">
            {HIGHLIGHTS.map((h, i) => (
              <motion.div
                key={h.title}
                initial={{ opacity: 0, x: -10 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{ delay: 0.1 + i * 0.1 }}
                className="flex items-start gap-3"
              >
                <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-white/15">
                  <h.icon className="h-4 w-4" />
                </span>
                <div>
                  <p className="text-sm font-semibold">{h.title}</p>
                  <p className="text-sm text-white/70">{h.body}</p>
                </div>
              </motion.div>
            ))}
          </div>
        </div>

        <p className="relative text-xs text-white/60">© {new Date().getFullYear()} CareerPilot AI</p>
      </div>

      {/* Form column */}
      <div className="flex items-center justify-center bg-background p-6 sm:p-12">
        <motion.div
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.3 }}
          className="w-full max-w-sm"
        >
          {children}
        </motion.div>
      </div>
    </div>
  );
}
