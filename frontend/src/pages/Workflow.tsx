import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '@/lib/api';

interface Run {
  threadId: string;
  status: string;
  targetRole: string;
  resumeScore: number | null;
  atsScore: number | null;
  jobMatchScore: number | null;
  interviewReadinessScore: number | null;
  state: string;
  createdAt: string;
}

export default function Workflow() {
  const qc = useQueryClient();
  const [form, setForm] = useState({
    resumeId: '',
    targetRole: 'Senior Software Engineer',
    targetSeniority: 'Senior',
    targetLocations: 'Remote, US',
    jobIds: '',
  });
  const [running, setRunning] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const { data: runs = [] } = useQuery<Run[]>({
    queryKey: ['workflows'],
    queryFn: async () => (await api.get('/api/workflows')).data,
  });

  async function start() {
    setErr(null);
    setRunning(true);
    try {
      await api.post('/api/workflows/run', {
        resumeId: form.resumeId,
        jobIds: form.jobIds.split(',').map((s) => s.trim()).filter(Boolean),
        targetRole: form.targetRole,
        targetSeniority: form.targetSeniority,
        targetLocations: form.targetLocations.split(',').map((s) => s.trim()).filter(Boolean),
      });
      qc.invalidateQueries({ queryKey: ['workflows'] });
      qc.invalidateQueries({ queryKey: ['dashboard'] });
    } catch (e: any) {
      setErr(e?.response?.data?.message || 'Workflow failed');
    } finally {
      setRunning(false);
    }
  }

  async function resume(threadId: string, decision: 'approved' | 'rejected') {
    await api.post(`/api/workflows/${threadId}/resume`, { decision });
    qc.invalidateQueries({ queryKey: ['workflows'] });
    qc.invalidateQueries({ queryKey: ['dashboard'] });
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">AI Career Workflow</h1>

      <div className="bg-white border rounded-lg p-4 space-y-3">
        <h2 className="font-semibold">Start a new run</h2>
        {(['resumeId', 'targetRole', 'targetSeniority', 'targetLocations', 'jobIds'] as const).map((k) => (
          <input key={k} className="w-full border rounded-md p-2"
                 placeholder={k === 'jobIds' ? 'jobIds (comma-separated UUIDs)' : k}
                 value={(form as any)[k]}
                 onChange={(e) => setForm({ ...form, [k]: e.target.value })} />
        ))}
        {err && <div className="text-rose-600 text-sm">{err}</div>}
        <button className="bg-brand-600 text-white px-3 py-2 rounded-md text-sm disabled:opacity-60"
                onClick={start} disabled={running}>
          {running ? 'Running…' : 'Start workflow'}
        </button>
      </div>

      <div className="space-y-3">
        <h2 className="font-semibold">Recent runs</h2>
        {runs.map((r) => (
          <div key={r.threadId} className="bg-white border rounded-lg p-4 text-sm">
            <div className="flex justify-between">
              <span className="font-mono">{r.threadId.slice(0, 8)}</span>
              <span className="font-medium">{r.status}</span>
            </div>
            <div className="grid grid-cols-4 gap-3 mt-2">
              <div>Resume: <b>{r.resumeScore ?? '—'}</b></div>
              <div>ATS: <b>{r.atsScore ?? '—'}</b></div>
              <div>Match: <b>{r.jobMatchScore ?? '—'}</b></div>
              <div>Interview: <b>{r.interviewReadinessScore ?? '—'}</b></div>
            </div>
            {r.status === 'INTERRUPTED' && (
              <div className="mt-3 flex gap-2">
                <button className="bg-emerald-600 text-white px-3 py-1 rounded-md text-xs"
                        onClick={() => resume(r.threadId, 'approved')}>Approve</button>
                <button className="bg-rose-600 text-white px-3 py-1 rounded-md text-xs"
                        onClick={() => resume(r.threadId, 'rejected')}>Reject</button>
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
