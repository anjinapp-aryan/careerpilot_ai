package ai.careerpilot.skillgap;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Phase 10 — thrown when a mission has no Skill Gap analysis yet (or the mission doesn't belong
 * to the caller). Same flat-404, security-conscious convention as {@code MissionNotFoundException}
 * — never distinguishes "no analysis yet" from "not your mission" in the response.
 */
public class SkillGapAnalysisNotFoundException extends NoSuchElementException {
    public SkillGapAnalysisNotFoundException(UUID missionId) {
        super("No Skill Gap analysis found for mission: " + missionId);
    }
}
