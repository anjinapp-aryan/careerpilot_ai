package ai.careerpilot.career.monitor;

import java.util.List;

/** Phase 11.5 — ranks/dedupes raw {@link CareerAlert}s into the subset actually worth surfacing. */
public interface CareerRecommendationEngine {

    List<CareerAlert> prioritize(List<CareerAlert> alerts, int limit);
}
