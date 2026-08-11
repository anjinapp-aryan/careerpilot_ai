import { describe, expect, it, vi, beforeEach } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '@/test/testUtils';
import AiMemory from './AiMemory';
import { api } from '@/lib/api';
import type {
  CandidateProfileDto,
  CareerDecisionMemory,
  CareerMemorySummary,
  OptimizationResponse,
} from '@/types/workflow';

vi.mock('@/lib/api', () => ({
  api: { get: vi.fn(), post: vi.fn() },
}));

const summary: CareerMemorySummary = {
  totalMemories: 3,
  verifiedCount: 2,
  needsReviewCount: 0,
  conflictingCount: 0,
  lowConfidenceCount: 0,
  averageConfidence: 0.91,
  lastUpdated: new Date().toISOString(),
};

const profile: CandidateProfileDto = {
  currentRole: 'Senior Software Engineer / Architect',
  yearsExperience: 13,
  seniority: 'Senior',
  skills: ['Java', 'Spring Boot'],
  targetRoles: ['Senior Java Engineer', 'Staff Engineer'],
  domains: [],
  languages: [],
  homeCountry: null,
  preferredCountries: ['Germany', 'Netherlands'],
  preferredCities: [],
  workModes: ['Remote', 'Hybrid'],
  visaRequired: true,
  salaryMin: null,
  salaryTarget: 120000,
  salaryCurrency: 'EUR',
  excludedRoles: ['Junior roles'],
  profileSummary: null,
  confidenceScore: 0.94,
  technologies: ['Java', 'Spring Boot', 'AWS', 'Kubernetes'],
  certifications: [],
  industries: ['Fintech'],
  leadershipExperience: true,
  cloudExpertise: true,
  careerGoals: ['Principal Engineer'],
  updatedAt: new Date().toISOString(),
};

const memory: CareerDecisionMemory = {
  id: 'm1',
  userId: 'u1',
  decisionType: 'TECHNOLOGY_POSITIVE',
  category: 'TECHNOLOGY',
  value: 'GraphQL',
  reason: 'User stated preference',
  confidence: 0.9,
  source: 'CONVERSATION',
  userConfirmed: false,
  createdAt: new Date().toISOString(),
  usageCount: 4,
};

function mockGet(overrides: Record<string, unknown> = {}) {
  const routes: Record<string, unknown> = {
    '/api/career-memory/summary': summary,
    '/api/candidate-profile': profile,
    '/api/career-memory': [memory],
    '/api/career-memory/timeline': [memory],
    '/api/interviews': [],
    '/api/diagnostics/career-memory': { enabled: true, copilotContextEnabled: true, totalMemories: 3, executorActiveCount: 0, executorQueueSize: 0, health: 'UP' },
    '/api/intelligence/optimization': { enabled: false, snapshot: null, recommendations: [] } satisfies OptimizationResponse,
    ...overrides,
  };
  vi.mocked(api.get).mockImplementation(async (url: string) => {
    if (url in routes) return { data: routes[url] };
    throw new Error(`unmocked GET ${url}`);
  });
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('AiMemory — Career Intelligence page', () => {
  it('shows the empty state when nothing has been learned yet', async () => {
    mockGet({
      '/api/career-memory/summary': { ...summary, totalMemories: 0 },
      '/api/candidate-profile': null,
      '/api/career-memory': [],
    });
    renderWithProviders(<AiMemory />);
    expect(await screen.findByText(/nothing learned yet/i)).toBeInTheDocument();
  });

  it('renders the hero with the real career identity and confidence, not a fabricated placeholder', async () => {
    mockGet();
    renderWithProviders(<AiMemory />);
    expect((await screen.findAllByText('Senior Software Engineer / Architect')).length).toBeGreaterThan(0);
    expect(screen.getAllByText('91%').length).toBeGreaterThan(0);
  });

  it('renders Career Identity & Job Search DNA with target roles and locations grouped, not scattered tiny cards', async () => {
    mockGet();
    renderWithProviders(<AiMemory />);
    await screen.findByText(/career identity & job search dna/i);
    expect(screen.getByText('Staff Engineer')).toBeInTheDocument();
    expect(screen.getAllByText('Germany').length).toBeGreaterThan(0);
    expect(screen.getByText('Netherlands')).toBeInTheDocument();
  });

  it('shows "Needs your attention" only when there is something genuinely to act on', async () => {
    mockGet();
    renderWithProviders(<AiMemory />);
    await screen.findByText(/career identity & job search dna/i);
    expect(screen.queryByText(/needs your attention/i)).not.toBeInTheDocument();
  });

  it('surfaces needs-review count as an actionable attention item when present', async () => {
    mockGet({ '/api/career-memory/summary': { ...summary, needsReviewCount: 3 } });
    renderWithProviders(<AiMemory />);
    expect(await screen.findByText(/needs your attention/i)).toBeInTheDocument();
    expect(screen.getByText(/3 memories need confirmation/i)).toBeInTheDocument();
  });

  it('omits the Learned Patterns section when production intelligence is disabled', async () => {
    mockGet();
    renderWithProviders(<AiMemory />);
    await screen.findByText(/career identity & job search dna/i);
    expect(screen.queryByText(/what careerpilot learned from your behavior/i)).not.toBeInTheDocument();
  });

  it('renders real evidence-backed findings when production intelligence is enabled', async () => {
    const optimization: OptimizationResponse = {
      enabled: true,
      recommendations: [],
      snapshot: {
        generatedAt: new Date().toISOString(),
        resume: null,
        countries: [
          {
            dimension: 'COUNTRY', key: 'Germany', applications: 12, interviews: 5, offers: 2,
            successRate: 0.42,
            evidence: { source: 'SuccessPatternEngine', sampleSize: 12, supporting: { interviews: 5 }, confidence: 'MEDIUM', citation: '12 observations, 5 interviews (source: SuccessPatternEngine)' },
          },
        ],
        companies: [],
        skills: [],
        ats: null,
        notes: [],
      },
    };
    mockGet({ '/api/intelligence/optimization': optimization });
    renderWithProviders(<AiMemory />);
    expect(await screen.findByText(/what careerpilot learned from your behavior/i)).toBeInTheDocument();
    expect(screen.getByText('42% success')).toBeInTheDocument();
    expect(screen.getByText(/12 observations, 5 interviews/i)).toBeInTheDocument();
  });

  it('confirming a memory calls the confirm endpoint', async () => {
    mockGet();
    vi.mocked(api.post).mockResolvedValue({ data: {} });
    const user = userEvent.setup();
    renderWithProviders(<AiMemory />);
    await screen.findByText('GraphQL');
    await user.click(screen.getByRole('button', { name: /confirm/i }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/career-memory/m1/confirm'));
  });

  it('forgetting a memory calls the forget endpoint', async () => {
    mockGet();
    vi.mocked(api.post).mockResolvedValue({ data: {} });
    const user = userEvent.setup();
    renderWithProviders(<AiMemory />);
    await screen.findByText('GraphQL');
    await user.click(screen.getByRole('button', { name: /forget/i }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/career-memory/m1/forget'));
  });

  it('opens the Teach CareerPilot dialog from the header action', async () => {
    mockGet();
    const user = userEvent.setup();
    renderWithProviders(<AiMemory />);
    await screen.findByText(/career identity & job search dna/i);
    await user.click(screen.getByRole('button', { name: /teach careerpilot/i }));
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(within(screen.getByRole('dialog')).getByText(/teach careerpilot/i)).toBeInTheDocument();
  });

  it('keeps Advanced / Diagnostics collapsed by default and expands on click', async () => {
    mockGet();
    const user = userEvent.setup();
    renderWithProviders(<AiMemory />);
    await screen.findByText(/career identity & job search dna/i);
    expect(screen.queryByText(/system diagnostics/i)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /advanced \/ diagnostics/i }));
    expect(await screen.findByText(/system diagnostics/i)).toBeInTheDocument();
  });
});
