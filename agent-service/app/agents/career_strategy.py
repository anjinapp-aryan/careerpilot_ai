"""Career Strategy Agent — produces a 12-month growth roadmap."""
from __future__ import annotations

import json
import logging

from ..workflow_ai_gateway import get_workflow_ai_gateway
from ..state import CareerState

log = logging.getLogger(__name__)

SYSTEM = "You are a principal engineering career coach. Output strict JSON only."

SCHEMA = {
    "type": "object",
    "properties": {
        "career_roadmap": {
            "type": "object",
            "properties": {
                "north_star_role": {"type": "string"},
                "horizon_3_months": {"type": "array", "items": {"type": "string"}},
                "horizon_6_months": {"type": "array", "items": {"type": "string"}},
                "horizon_12_months": {"type": "array", "items": {"type": "string"}},
                "recommended_certifications": {"type": "array", "items": {"type": "string"}},
            },
            "required": ["north_star_role", "horizon_3_months", "horizon_6_months", "horizon_12_months"],
        },
        "skill_gaps": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["career_roadmap", "skill_gaps"],
}


def career_strategy_node(state: CareerState) -> dict:
    profile = state.get("candidate_profile") or {}
    skills = state.get("extracted_skills") or []
    missing_kw = state.get("missing_keywords") or []

    prompt = (
        "Produce a career growth roadmap. Compute skill_gaps by combining missing_keywords and "
        "market-priority skills the candidate lacks. Build a horizoned roadmap (3/6/12 months) "
        "with specific actions and recommended certifications.\n\n"
        f"PROFILE: {json.dumps(profile)}\n"
        f"SKILLS: {json.dumps(skills)}\n"
        f"MISSING_KEYWORDS: {json.dumps(missing_kw)}\n"
        f"TARGET_ROLE: {state.get('target_role', '')}\n"
        f"TARGET_SENIORITY: {state.get('target_seniority', '')}"
    )
    try:
        log.info("career_strategy: stage started")
        gateway = get_workflow_ai_gateway()
        result = gateway.generate_structured_response(prompt, SCHEMA, system=SYSTEM, stage="career_strategy")
        log.info("career_strategy: stage completed successfully")
        return {
            "career_roadmap": result.get("career_roadmap", {}),
            "skill_gaps": result.get("skill_gaps", []),
        }
    except Exception as e:  # noqa: BLE001
        log.error("career_strategy: stage failed", extra={"error": str(e)}, exc_info=True)
        return {"errors": [f"career_strategy: {e}"], "career_roadmap": {}, "skill_gaps": []}
