package ai.careerpilot.resumetailoring.scoring;

import ai.careerpilot.resumetailoring.scoring.ResumeImprovementCalculator.ImprovementResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2D.1 Step 8 — deterministic ATS keyword-coverage scoring: before/after/improvement must be
 * simple, explainable arithmetic (percentage of job skills present in the resume text), matching
 * the {@code JobScoring} philosophy of no LLM in the scoring path itself.
 */
class ResumeImprovementCalculatorTest {

    private final ResumeImprovementCalculator calculator = new ResumeImprovementCalculator();

    @Test
    void scoresHigherWhenTailoredResumeCoversMoreJobSkills() {
        String original = "Backend engineer with Java experience.";
        String tailored = "Backend engineer with Java, Kubernetes, and AWS experience.";
        List<String> jobSkills = List.of("Java", "Kubernetes", "AWS");

        ImprovementResult result = calculator.calculate(original, tailored, jobSkills);

        assertEquals(33, result.atsBefore()); // 1/3 skills present
        assertEquals(100, result.atsAfter());  // 3/3 skills present
        assertEquals(67, result.improvementScore());
    }

    @Test
    void noJobSkillsYieldsZeroScoreNotDivideByZero() {
        ImprovementResult result = calculator.calculate("some text", "other text", List.of());
        assertEquals(0, result.atsBefore());
        assertEquals(0, result.atsAfter());
        assertEquals(0, result.improvementScore());
    }

    @Test
    void matchIsCaseInsensitive() {
        int score = calculator.atsScore("Experienced with JAVA and kubernetes", List.of("java", "Kubernetes"));
        assertEquals(100, score);
    }

    @Test
    void nullResumeTextScoresZeroWithoutThrowing() {
        assertEquals(0, calculator.atsScore(null, List.of("Java")));
    }
}
