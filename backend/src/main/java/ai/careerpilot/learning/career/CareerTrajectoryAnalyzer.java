package ai.careerpilot.learning.career;

import ai.careerpilot.domain.CareerLearning;
import ai.careerpilot.repo.CareerLearningRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Phase 6.6 — renders a short, deterministic trajectory summary from the user's top
 * {@link CareerLearning} rows per dimension. Plain string templating, no LLM.
 */
@Component
public class CareerTrajectoryAnalyzer {

    private final CareerLearningRepository careerLearning;

    public CareerTrajectoryAnalyzer(CareerLearningRepository careerLearning) {
        this.careerLearning = careerLearning;
    }

    public String recommend(UUID userId) {
        String topCompany = topKey(userId, CareerLearning.DIM_COMPANY);
        String topSkill = topKey(userId, CareerLearning.DIM_SKILL);
        String topIndustry = topKey(userId, CareerLearning.DIM_INDUSTRY);
        String topLocation = topKey(userId, CareerLearning.DIM_LOCATION);

        StringBuilder sb = new StringBuilder("Based on your learned history, focus on ");
        boolean any = false;
        if (topIndustry != null) { sb.append(topIndustry).append(" roles"); any = true; }
        if (topSkill != null) { sb.append(any ? " leveraging " : "").append(topSkill); any = true; }
        if (topCompany != null) { sb.append(any ? "; companies like " : "companies like ").append(topCompany).append(" respond well"); any = true; }
        if (topLocation != null) { sb.append(any ? "; " : "").append(topLocation).append(" has been your strongest location"); any = true; }
        if (!any) return "Not enough learned history yet to recommend a trajectory.";
        return sb.append(".").toString();
    }

    private String topKey(UUID userId, String dimension) {
        List<CareerLearning> rows = careerLearning.findByUserIdAndDimensionOrderByScoreDesc(userId, dimension);
        return rows.isEmpty() ? null : rows.get(0).getDimensionKey();
    }
}
