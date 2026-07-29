"""Learning Roadmap Agent — turns the classified skill gaps into a prioritized, estimated plan.

For each gap: estimated duration (weeks), difficulty, dependency on another skill in the same
roadmap, and business impact of closing it. Also estimates total months to close all gaps,
respecting the candidate's target timeline where one was supplied.
"""
from __future__ import annotations

import json
import logging

from ..state import SkillGapState
from ...agent_support import call_structured_agent

log = logging.getLogger(__name__)

SYSTEM = "You are a senior technical learning-and-development architect. Output strict JSON only."

SCHEMA = {
    "type": "object",
    "properties": {
        "roadmap": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "skill": {"type": "string"},
                    "priority": {"type": "string"},
                    "estimated_duration_weeks": {"type": "integer"},
                    "difficulty": {"type": "string", "enum": ["LOW", "MEDIUM", "HIGH"]},
                    "depends_on": {"type": "array", "items": {"type": "string"}},
                    "business_impact": {"type": "string", "enum": ["LOW", "MEDIUM", "HIGH"]},
                },
                "required": [
                    "skill", "priority", "estimated_duration_weeks", "difficulty",
                    "depends_on", "business_impact",
                ],
            },
        },
        "estimated_completion_months": {"type": "integer"},
    },
    "required": ["roadmap", "estimated_completion_months"],
}


def learning_roadmap_node(state: SkillGapState) -> dict:
    critical = state.get("critical_skill_gaps") or []
    important = state.get("important_skill_gaps") or []
    optional = state.get("optional_skill_gaps") or []
    timeline_months = (state.get("mission_context") or {}).get("timeline_months")

    if not (critical or important or optional):
        log.info("learning_roadmap: no gaps to plan for")
        return {"recommended_learning_roadmap": [], "estimated_completion_months": 0}

    prompt = (
        "Build a prioritized learning roadmap for these skill gaps, ordered CRITICAL first. "
        "For each skill, estimate learning duration in weeks, difficulty, whether it depends on "
        "another skill already in this same list, and the business impact of closing this gap. "
        "Also estimate the total number of months to close all gaps, respecting the candidate's "
        f"target timeline of {timeline_months or 'no fixed'} months where feasible.\n\n"
        f"CRITICAL: {json.dumps(critical)}\n"
        f"IMPORTANT: {json.dumps(important)}\n"
        f"OPTIONAL: {json.dumps(optional)}"
    )

    def on_success(result: dict) -> dict:
        return {
            "recommended_learning_roadmap": result.get("roadmap") or [],
            "estimated_completion_months": result.get("estimated_completion_months") or 0,
        }

    return call_structured_agent(
        stage="skill_gap_learning_roadmap", log_name="learning_roadmap",
        prompt=prompt, schema=SCHEMA, system=SYSTEM, on_success=on_success,
        fallback={"recommended_learning_roadmap": [], "estimated_completion_months": 0},
        log=log,
    )
