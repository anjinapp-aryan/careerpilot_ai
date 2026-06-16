import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';

interface App {
  id: string;
  jobId: string;
  status: string;
  matchScore: number | null;
  atsScore: number | null;
  notes: string | null;
  createdAt: string;
}

const STATUSES = ['SAVED', 'APPLIED', 'INTERVIEWING', 'OFFER', 'REJECTED', 'WITHDRAWN'];

export default function Applications() {
  const qc = useQueryClient();
  const { data = [] } = useQuery<App[]>({
    queryKey: ['applications'],
    queryFn: async () => (await api.get('/api/applications')).data,
  });

  async function updateStatus(id: string, status: string) {
    await api.patch(`/api/applications/${id}`, { status });
    qc.invalidateQueries({ queryKey: ['applications'] });
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">Applications</h1>
      <div className="bg-white rounded-lg border border-slate-100 divide-y">
        {data.length === 0 && <div className="p-4 text-slate-500 text-sm">No applications yet.</div>}
        {data.map((a) => (
          <div key={a.id} className="p-3 grid grid-cols-5 gap-3 text-sm items-center">
            <span className="font-mono">{a.jobId.slice(0, 8)}</span>
            <span>Match: {a.matchScore ?? '—'}</span>
            <span>ATS: {a.atsScore ?? '—'}</span>
            <select className="border rounded-md p-1" value={a.status}
                    onChange={(e) => updateStatus(a.id, e.target.value)}>
              {STATUSES.map((s) => <option key={s}>{s}</option>)}
            </select>
            <span className="text-slate-500">{new Date(a.createdAt).toLocaleDateString()}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
