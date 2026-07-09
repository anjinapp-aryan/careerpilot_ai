package ai.careerpilot.workflow.career;

/**
 * Phase 3A.6 — the pure, deterministic probability math for career intelligence. No I/O: unit-tested
 * directly. All outputs are clamped to [0,1]. Overall career success is a weighted blend that favours
 * the harder-to-reach signal (a real offer) over merely reaching interviews.
 */
public final class CareerProbability {

    private CareerProbability() {}

    static final double INTERVIEW_WEIGHT = 0.4;
    static final double OFFER_WEIGHT = 0.6;

    /** Blend interview-rate and offer-rate into an overall career-success probability, clamped to [0,1]. */
    public static double careerSuccess(double interviewRate, double offerRate) {
        double blended = INTERVIEW_WEIGHT * clamp(interviewRate) + OFFER_WEIGHT * clamp(offerRate);
        return clamp(blended);
    }

    public static double clamp(double v) {
        if (Double.isNaN(v) || v < 0) return 0.0;
        return Math.min(v, 1.0);
    }
}
