import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '@/lib/api';

interface Resume {
  id: string;
  filename: string;
  resumeScore: number | null;
  createdAt: string;
}

export default function Resumes() {
  const qc = useQueryClient();
  const [busy, setBusy] = useState(false);
  const { data = [] } = useQuery<Resume[]>({
    queryKey: ['resumes'],
    queryFn: async () => (await api.get('/api/resumes')).data,
  });

  async function upload(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    setBusy(true);
    const fd = new FormData();
    fd.append('file', file);
    try {
      await api.post('/api/resumes', fd, { headers: { 'Content-Type': 'multipart/form-data' } });
      qc.invalidateQueries({ queryKey: ['resumes'] });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Resumes</h1>
        <label className="bg-brand-600 text-white px-3 py-2 rounded-md cursor-pointer text-sm">
          {busy ? 'Uploading…' : 'Upload resume'}
          <input type="file" className="hidden" onChange={upload}
                 accept=".pdf,.doc,.docx,.txt" disabled={busy} />
        </label>
      </div>
      <div className="bg-white rounded-lg border border-slate-100 divide-y">
        {data.length === 0 && <div className="p-4 text-slate-500 text-sm">Upload a resume to begin.</div>}
        {data.map((r) => (
          <div key={r.id} className="p-3 flex justify-between text-sm">
            <span>{r.filename}</span>
            <span>{r.resumeScore ?? '—'}</span>
            <span className="text-slate-500">{new Date(r.createdAt).toLocaleString()}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
