interface Props {
  label: string;
  value: number;
  hint?: string;
}

export default function ScoreCard({ label, value, hint }: Props) {
  const color =
    value >= 80 ? 'text-emerald-600' : value >= 60 ? 'text-amber-600' : 'text-rose-600';
  return (
    <div className="rounded-xl bg-white p-5 shadow-sm border border-slate-100">
      <div className="text-xs uppercase tracking-wide text-slate-500">{label}</div>
      <div className={`text-3xl font-semibold mt-2 ${color}`}>{value}</div>
      {hint && <div className="text-xs text-slate-500 mt-1">{hint}</div>}
    </div>
  );
}
