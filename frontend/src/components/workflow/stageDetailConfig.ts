/**
 * Per-stage extraction config for the Workflow page's stage inspection panels.
 *
 * Keyed by the live `WorkflowAgent.name` label (the backend-sourced display name),
 * NOT the static `PIPELINE` placeholder array in Workflow.tsx — those labels can
 * differ slightly (e.g. "Interview Prep" vs "Interview Preparation").
 *
 * Each extractor reads only fields that actually exist in agent-service's
 * CareerState (see agent-service/app/state.py and app/agents/*.py) — no
 * fabricated metrics, no artifact/report fields.
 */

type StateBag = Record<string, unknown>;

export interface StageSection {
  summary?: (state: StateBag) => string | null;
  insights?: (state: StateBag) => string[];
  scores?: (state: StateBag) => { label: string; value: number | null }[];
  recommendations?: (state: StateBag) => string[];
  /** Which state[] keys feed the "Raw output" block for this stage. */
  rawKeys: string[];
}

function asRecord(v: unknown): StateBag | null {
  return v && typeof v === 'object' && !Array.isArray(v) ? (v as StateBag) : null;
}

function asArray(v: unknown): unknown[] {
  return Array.isArray(v) ? v : [];
}

function asStringArray(v: unknown): string[] {
  return asArray(v).filter((x): x is string => typeof x === 'string');
}

export const STAGE_DETAIL_CONFIG: Record<string, StageSection> = {
  'Resume Intelligence': {
    summary: (state) => {
      const profile = asRecord(state.candidate_profile);
      if (!profile) return null;
      const title = typeof profile.current_title === 'string' ? profile.current_title : null;
      const years = typeof profile.years_experience === 'number' ? profile.years_experience : null;
      const seniority = typeof profile.seniority === 'string' ? profile.seniority : null;
      const parts = [title, seniority, years != null ? `${years} yrs experience` : null].filter(Boolean);
      return (typeof profile.summary === 'string' && profile.summary) || (parts.length ? parts.join(' · ') : null);
    },
    insights: (state) => {
      const profile = asRecord(state.candidate_profile);
      const strengths = asStringArray(profile?.top_strengths);
      const achievements = asStringArray(profile?.achievements);
      const domains = asStringArray(profile?.domains);
      const skills = asStringArray(state.extracted_skills);
      return [
        ...strengths.map((s) => `Strength: ${s}`),
        ...achievements.map((a) => `Achievement: ${a}`),
        ...domains.map((d) => `Domain: ${d}`),
        ...(skills.length ? [`Skills extracted: ${skills.join(', ')}`] : []),
      ];
    },
    scores: (state) => [{ label: 'Resume Score', value: typeof state.resume_score === 'number' ? state.resume_score : null }],
    rawKeys: ['candidate_profile', 'extracted_skills', 'resume_score'],
  },

  'Job Discovery': {
    summary: (state) => {
      const jobs = asArray(state.ranked_jobs);
      return jobs.length ? `${jobs.length} job${jobs.length === 1 ? '' : 's'} ranked against this resume.` : null;
    },
    insights: (state) =>
      asArray(state.ranked_jobs)
        .slice(0, 5)
        .map((j) => {
          const job = asRecord(j);
          if (!job) return '';
          const id = typeof job.job_id === 'string' ? job.job_id : 'Job';
          const score = typeof job.match_score === 'number' ? `${job.match_score}% match` : null;
          const rationale = typeof job.rationale === 'string' ? job.rationale : null;
          return [id, score, rationale].filter(Boolean).join(' — ');
        })
        .filter(Boolean),
    scores: (state) => [{ label: 'Job Match Score', value: typeof state.job_match_score === 'number' ? state.job_match_score : null }],
    recommendations: (state) => {
      const top = asRecord(asArray(state.ranked_jobs)[0]);
      const missing = asStringArray(top?.missing_skills);
      return missing.map((s) => `Address missing skill for top match: ${s}`);
    },
    rawKeys: ['ranked_jobs', 'job_match_score'],
  },

  'ATS Optimization': {
    summary: (state) =>
      typeof state.ats_score === 'number' ? `Current ATS compatibility score: ${state.ats_score}/100.` : null,
    insights: (state) => asStringArray(state.missing_keywords).map((k) => `Missing keyword: ${k}`),
    scores: (state) => [{ label: 'ATS Score', value: typeof state.ats_score === 'number' ? state.ats_score : null }],
    recommendations: (state) => asStringArray(state.ats_optimization_plan),
    rawKeys: ['ats_score', 'missing_keywords', 'ats_optimization_plan'],
  },

  'Interview Preparation': {
    summary: (state) => {
      const plan = asRecord(state.interview_plan);
      if (!plan) return null;
      const counts = (['technical_questions', 'behavioral_questions', 'system_design_questions', 'leadership_questions'] as const)
        .map((k) => asArray(plan[k]).length)
        .reduce((a, b) => a + b, 0);
      return counts > 0 ? `${counts} interview questions prepared across technical, behavioral, system design, and leadership tracks.` : null;
    },
    insights: (state) => {
      const plan = asRecord(state.interview_plan);
      return asStringArray(plan?.study_topics).map((t) => `Study topic: ${t}`);
    },
    scores: (state) => [
      { label: 'Interview Readiness', value: typeof state.interview_readiness_score === 'number' ? state.interview_readiness_score : null },
    ],
    recommendations: (state) => {
      const plan = asRecord(state.interview_plan);
      return asStringArray(plan?.technical_questions).slice(0, 3).map((q) => `Practice: ${q}`);
    },
    rawKeys: ['interview_plan', 'interview_readiness_score'],
  },

  'Career Strategy': {
    summary: (state) => {
      const roadmap = asRecord(state.career_roadmap);
      const star = roadmap && typeof roadmap.north_star_role === 'string' ? roadmap.north_star_role : null;
      return star ? `North star role: ${star}.` : null;
    },
    insights: (state) => {
      const roadmap = asRecord(state.career_roadmap);
      return [
        ...asStringArray(roadmap?.horizon_3_months).map((s) => `3 months: ${s}`),
        ...asStringArray(roadmap?.horizon_6_months).map((s) => `6 months: ${s}`),
        ...asStringArray(roadmap?.horizon_12_months).map((s) => `12 months: ${s}`),
      ];
    },
    recommendations: (state) => {
      const roadmap = asRecord(state.career_roadmap);
      return [
        ...asStringArray(roadmap?.recommended_certifications).map((c) => `Certification: ${c}`),
        ...asStringArray(state.skill_gaps).map((s) => `Close skill gap: ${s}`),
      ];
    },
    rawKeys: ['career_roadmap', 'skill_gaps'],
  },

  'Salary Intelligence': {
    summary: (state) => {
      const insights = asRecord(state.salary_insights);
      if (!insights) return null;
      const currency = typeof insights.currency === 'string' ? insights.currency : '';
      const p50 = typeof insights.p50 === 'number' ? insights.p50 : null;
      return p50 != null ? `Median market salary: ${currency} ${p50.toLocaleString()}.` : null;
    },
    scores: (state) => {
      const insights = asRecord(state.salary_insights);
      if (!insights) return [];
      return (['p25', 'p50', 'p75', 'p90'] as const)
        .filter((k) => typeof insights[k] === 'number')
        .map((k) => ({ label: k.toUpperCase(), value: insights[k] as number }));
    },
    recommendations: (state) => {
      const insights = asRecord(state.salary_insights);
      return [
        ...asStringArray(insights?.negotiation_strategy),
        ...asStringArray(insights?.leverage_points).map((p) => `Leverage point: ${p}`),
      ];
    },
    rawKeys: ['salary_insights'],
  },

  'Human Approval': {
    // Approval/rejection audit (approvedBy/At, rejectedBy/At, feedback) is already
    // rendered in RunCard's audit block — this panel intentionally stays minimal to
    // avoid duplicating it, and just exposes the raw gate fields for inspection.
    rawKeys: ['awaiting_human_approval', 'human_decision', 'human_feedback'],
  },

  'Application Tracking': {
    summary: (state) => {
      const tracked = asRecord(state.tracked_application);
      return tracked && typeof tracked.status === 'string' ? `Application status: ${tracked.status}.` : null;
    },
    insights: (state) => {
      const tracked = asRecord(state.tracked_application);
      return asArray(tracked?.reminders)
        .map((r) => {
          const reminder = asRecord(r);
          if (!reminder) return '';
          const when = typeof reminder.when_iso === 'string' ? reminder.when_iso : null;
          const message = typeof reminder.message === 'string' ? reminder.message : null;
          return [when, message].filter(Boolean).join(' — ');
        })
        .filter(Boolean);
    },
    recommendations: (state) => {
      const tracked = asRecord(state.tracked_application);
      return asStringArray(tracked?.next_actions);
    },
    rawKeys: ['tracked_application'],
  },
};
