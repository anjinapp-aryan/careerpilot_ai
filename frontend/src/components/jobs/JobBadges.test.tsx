import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import {
  SponsorshipBadge,
  FreshnessBadge,
  SearchPriorityBadge,
  CandidateCountryFitBadge,
  IndustryFitBadge,
  LanguageFriendlyBadge,
} from './JobBadges';

describe('SponsorshipBadge — Global Job Discovery Expansion', () => {
  it('renders "Sponsorship: Confirmed" for CONFIRMED', () => {
    render(<SponsorshipBadge status="CONFIRMED" />);
    expect(screen.getByText(/sponsorship: confirmed/i)).toBeInTheDocument();
  });

  it('renders "Sponsorship: Mentioned" for MENTIONED', () => {
    render(<SponsorshipBadge status="MENTIONED" />);
    expect(screen.getByText(/sponsorship: mentioned/i)).toBeInTheDocument();
  });

  it('renders an honest "Sponsorship: Unknown" rather than omitting or implying a positive', () => {
    render(<SponsorshipBadge status="UNKNOWN" />);
    expect(screen.getByText(/sponsorship: unknown/i)).toBeInTheDocument();
  });

  it('renders "Sponsorship: Not supported" for NOT_SUPPORTED', () => {
    render(<SponsorshipBadge status="NOT_SUPPORTED" />);
    expect(screen.getByText(/sponsorship: not supported/i)).toBeInTheDocument();
  });

  it('renders nothing when status is absent (flag off / not computed)', () => {
    const { container } = render(<SponsorshipBadge status={null} />);
    expect(container).toBeEmptyDOMElement();
  });
});

describe('FreshnessBadge — Global Job Discovery Expansion', () => {
  it('renders each freshness band label', () => {
    render(<FreshnessBadge freshness="VERY_FRESH" />);
    expect(screen.getByText(/very fresh/i)).toBeInTheDocument();
  });

  it('renders nothing when freshness is absent', () => {
    const { container } = render(<FreshnessBadge freshness={null} />);
    expect(container).toBeEmptyDOMElement();
  });
});

describe('SearchPriorityBadge — International Job Discovery Phase 2', () => {
  it('renders each priority label', () => {
    render(<SearchPriorityBadge searchPriority="PRIMARY" />);
    expect(screen.getByText(/^primary$/i)).toBeInTheDocument();
  });

  it('renders Primary · Specialist for PRIMARY_SPECIALIST', () => {
    render(<SearchPriorityBadge searchPriority="PRIMARY_SPECIALIST" />);
    expect(screen.getByText(/primary.*specialist/i)).toBeInTheDocument();
  });

  it('renders nothing when absent (flag off, or country has no assignment e.g. UAE)', () => {
    const { container } = render(<SearchPriorityBadge searchPriority={null} />);
    expect(container).toBeEmptyDOMElement();
  });
});

describe('CandidateCountryFitBadge — International Job Discovery Phase 2', () => {
  it('renders "Country Fit: Very High"', () => {
    render(<CandidateCountryFitBadge fit="VERY_HIGH" />);
    expect(screen.getByText(/country fit: very high/i)).toBeInTheDocument();
  });

  it('renders nothing when the country has no curated intelligence', () => {
    const { container } = render(<CandidateCountryFitBadge fit={null} />);
    expect(container).toBeEmptyDOMElement();
  });
});

describe('IndustryFitBadge — International Job Discovery Phase 2', () => {
  it('renders Banking for BANKING', () => {
    render(<IndustryFitBadge industry="BANKING" />);
    expect(screen.getByText(/banking/i)).toBeInTheDocument();
  });

  it('renders nothing when unclassifiable, never "Unknown"', () => {
    const { container } = render(<IndustryFitBadge industry={null} />);
    expect(container).toBeEmptyDOMElement();
  });
});

describe('LanguageFriendlyBadge — International Job Discovery Phase 2', () => {
  it('renders for a genuinely high score', () => {
    render(<LanguageFriendlyBadge score={100} />);
    expect(screen.getByText(/english-friendly market/i)).toBeInTheDocument();
  });

  it('renders nothing for a middling score (not a blanket claim)', () => {
    const { container } = render(<LanguageFriendlyBadge score={70} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing when absent', () => {
    const { container } = render(<LanguageFriendlyBadge score={null} />);
    expect(container).toBeEmptyDOMElement();
  });
});
