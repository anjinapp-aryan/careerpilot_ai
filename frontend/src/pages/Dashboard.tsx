import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import ScoreCard from '@/components/ScoreCard';

interface Snapshot {
  careerHealthScore: number;
  resumeScore: number;
  atsScore: number;
  jobMatchScore: number;
  interviewReadinessScore: number;
  offerProbabilityScore: number;
  applications: Record<string, number>;
  resumes: number;
  recentRuns: Array<{ threadId: string; status: string; targetRole: string; createdAt: string }>;
}

export default function Dashboard() {
  const { data, isLoading } = useQuery<Snapshot>({
    queryKey: ['dashboard'],
    queryFn: async () => (await api.get('/api/dashboard')).data,
  });

  if (isLoading || !data) return <div>Loading…</div>;

  return (
    <div className="space-y-8">
      <h1 className="text-2xl font-semibold">Career Health</h1>
      <div className="grid grid-cols-3 gap-4">
        <ScoreCard label="Career Health" value={data.careerHealthScore} hint="Composite of all signals" />
        <ScoreCard label="Resume Score" value={data.resumeScore} />
        <ScoreCard label="ATS Score" value={data.atsScore} />
        <ScoreCard label="Job Match" value={data.jobMatchScore} />
        <ScoreCard label="Interview Readiness" value={data.interviewReadinessScore} />
        <ScoreCard label="Offer Probability" value={data.offerProbabilityScore} />
      </div>

      <section>
        <h2 className="text-lg font-semibold mb-3">Applications</h2>
        <div className="grid grid-cols-5 gap-3">
          {Object.entries(data.applications).map(([k, v]) => (
            <div key={k} className="rounded-lg bg-white p-4 border border-slate-100">
              <div className="text-xs uppercase text-slate-500">{k}</div>
              <div className="text-2xl font-semibold">{v}</div>
            </div>
          ))}
        </div>
      </section>

      <section>
        <h2 className="text-lg font-semibold mb-3">Recent AI workflow runs</h2>
        <div className="bg-white rounded-lg border border-slate-100 divide-y">
          {data.recentRuns.length === 0 && <div className="p-4 text-slate-500 text-sm">No runs yet.</div>}
          {data.recentRuns.map((r) => (
            <div key={r.threadId} className="p-3 flex justify-between text-sm">
              <span className="font-mono text-slate-600">{r.threadId.slice(0, 8)}</span>
              <span>{r.targetRole}</span>
              <span className="font-medium">{r.status}</span>
              <span className="text-slate-500">{new Date(r.createdAt).toLocaleString()}</span>
            </div>
          ))}
        </div>
      </section>
    </div>
  );
}
