import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '@/lib/api';

interface Job {
  id: string;
  title: string;
  company: string;
  location: string;
  description: string;
  salaryRange?: string;
}

export default function Jobs() {
  const qc = useQueryClient();
  const [q, setQ] = useState('');
  const [showNew, setShowNew] = useState(false);
  const [draft, setDraft] = useState({ title: '', company: '', location: '', description: '', salaryRange: '' });

  const { data } = useQuery({
    queryKey: ['jobs', q],
    queryFn: async () => (await api.get('/api/jobs', { params: { q } })).data,
  });

  async function create() {
    await api.post('/api/jobs', draft);
    setShowNew(false);
    setDraft({ title: '', company: '', location: '', description: '', salaryRange: '' });
    qc.invalidateQueries({ queryKey: ['jobs'] });
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Jobs</h1>
        <button className="bg-brand-600 text-white px-3 py-2 rounded-md text-sm" onClick={() => setShowNew(!showNew)}>
          {showNew ? 'Cancel' : 'Add job'}
        </button>
      </div>
      <input className="w-full border rounded-md p-2" placeholder="Search title or company"
             value={q} onChange={(e) => setQ(e.target.value)} />

      {showNew && (
        <div className="bg-white border rounded-lg p-4 space-y-2">
          {(['title', 'company', 'location', 'salaryRange'] as const).map((k) => (
            <input key={k} className="w-full border rounded-md p-2"
                   placeholder={k}
                   value={(draft as any)[k]}
                   onChange={(e) => setDraft({ ...draft, [k]: e.target.value })} />
          ))}
          <textarea className="w-full border rounded-md p-2 h-32" placeholder="Job description"
                    value={draft.description}
                    onChange={(e) => setDraft({ ...draft, description: e.target.value })} />
          <button className="bg-brand-600 text-white px-3 py-2 rounded-md text-sm" onClick={create}>Save</button>
        </div>
      )}

      <div className="bg-white rounded-lg border border-slate-100 divide-y">
        {(data?.content ?? []).map((j: Job) => (
          <div key={j.id} className="p-3 text-sm">
            <div className="font-medium">{j.title} <span className="text-slate-500">— {j.company}</span></div>
            <div className="text-slate-500">{j.location} {j.salaryRange ? `• ${j.salaryRange}` : ''}</div>
          </div>
        ))}
      </div>
    </div>
  );
}
