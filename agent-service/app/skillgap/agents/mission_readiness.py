"""Mission Readiness Agent — readiness score, confidence, mission progress, and a narrative.

Hybrid by design: the numeric outputs (readiness_score, confidence, mission_progress) are computed
deterministically from the gap counts — transparent, reproducible, and testable without an AI
Gateway call, the same "never invent a computed number" discipline used throughout this platform
(e.g. `DailyCareerSummaryGenerator`'s snapshot numbers are always computed, never invented; the AI
call there only rewrites the narrative). Only the qualitative strengths/risks/recommendations
narrative goes through the AI Gateway, once per run.
"""
from __future__ import annotations

import json
import logging

from ..state import SkillGapState
from ...agent_support import call_structured_agent

log = logging.getLogger(__name__)

SYSTEM = "You are a principal career strategy advisor. Output strict JSON only."

SCHEMA = {
    "type": "object",
    "properties": {
        "strengths": {"type": "array", "items": {"type": "string"}},
        "risks": {"type": "array", "items": {"type": "string"}},
        "recommendations": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["strengths", "risks", "recommendations"],
}


def _readiness_score(critical: int, important: int, optional: int) -> int:
    score = 100 - (critical * 15) - (important * 7) - (optional * 2)
    return max(0, min(100, score))


def _confidence(critical: int, important: int, has_errors: bool) -> float:
    base = 0.95 if not has_errors else 0.6
    penalty = min(0.4, critical * 0.05 + important * 0.02)
    return round(max(0.3, base - penalty), 2)


def mission_readiness_node(state: SkillGapState) -> dict:
    critical = state.get("critical_skill_gaps") or []
    important = state.get("important_skill_gaps") or []
    optional = state.get("optional_skill_gaps") or []
    has_errors = bool(state.get("errors"))

    readiness_score = _readiness_score(len(critical), len(important), len(optional))
    confidence = _confidence(len(critical), len(important), has_errors)
    mission_progress = round(readiness_score / 100, 2)

    prompt = (
        "Given this skill gap analysis for a candidate's career mission, identify the "
        "candidate's key strengths (what already positions them well), the top risks to "
        "achieving this mission on the stated timeline, and concrete, prioritized "
        "recommendations for what to do next.\n\n"
        f"MISSION: {json.dumps(state.get('mission_context', {}))}\n"
        f"PROFILE: {json.dumps(state.get('candidate_profile', {}))}\n"
        f"CRITICAL_GAPS: {json.dumps(critical)}\n"
        f"IMPORTANT_GAPS: {json.dumps(important)}\n"
        f"READINESS_SCORE: {readiness_score}"
    )

    def on_success(result: dict) -> dict:
        return {
            "readiness_score": readiness_score,
            "confidence": confidence,
            "mission_progress": mission_progress,
            "strengths": result.get("strengths") or [],
            "risks": result.get("risks") or [],
            "recommendations": result.get("recommendations") or [],
        }

    return call_structured_agent(
        stage="skill_gap_mission_readiness", log_name="mission_readiness",
        prompt=prompt, schema=SCHEMA, system=SYSTEM, on_success=on_success,
        fallback={
            "readiness_score": readiness_score, "confidence": confidence,
            "mission_progress": mission_progress, "strengths": [], "risks": [], "recommendations": [],
        },
        log=log,
    )
