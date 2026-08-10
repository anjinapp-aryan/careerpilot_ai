import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { SponsorshipBadge, FreshnessBadge } from './JobBadges';

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
