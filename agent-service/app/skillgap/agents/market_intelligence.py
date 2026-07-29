"""Market Intelligence Agent — identifies skills the market expects for the target role/country.

Each expected skill is returned with a CRITICAL/IMPORTANT/OPTIONAL priority and a one-sentence
reason — the Skill Gap Agent (next node) is purely a deterministic set-difference against this
list, so all judgment about *why a skill matters* lives here, in one place.
"""
from __future__ import annotations

import json
import logging

from ..state import SkillGapState
from ...agent_support import call_structured_agent

log = logging.getLogger(__name__)

SYSTEM = "You are a senior technical recruiter and market analyst. Output strict JSON only."

SCHEMA = {
    "type": "object",
    "properties": {
        "expected_skills": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "skill": {"type": "string"},
                    "priority": {"type": "string", "enum": ["CRITICAL", "IMPORTANT", "OPTIONAL"]},
                    "reason": {"type": "string"},
                },
                "required": ["skill", "priority", "reason"],
            },
        },
        "country_notes": {"type": "string"},
        "industry_trends": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["expected_skills", "country_notes", "industry_trends"],
}


def market_intelligence_node(state: SkillGapState) -> dict:
    mission_context = state.get("mission_context") or {}
    target_role = mission_context.get("target_role", "")
    target_countries = mission_context.get("target_countries") or []
    profile = state.get("candidate_profile") or {}

    countries_desc = ", ".join(target_countries) if target_countries else "the candidate's target market"
    prompt = (
        f"Identify the skills the job market expects for a '{target_role}' role in "
        f"{countries_desc}. For each expected skill, classify its priority as CRITICAL, "
        "IMPORTANT, or OPTIONAL for this specific role and country, with a one-sentence reason. "
        "Also summarize country-specific hiring expectations and current industry trends for "
        "this role.\n\n"
        f"CANDIDATE_EXPERIENCE_YEARS: {profile.get('experience_years', 0)}\n"
        f"CANDIDATE_KNOWN_SKILLS: {json.dumps(profile.get('skills', []))}"
    )

    def on_success(result: dict) -> dict:
        return {"market_expectations": {
            "expected_skills": result.get("expected_skills") or [],
            "country_notes": result.get("country_notes") or "",
            "industry_trends": result.get("industry_trends") or [],
        }}

    return call_structured_agent(
        stage="skill_gap_market_intelligence", log_name="market_intelligence",
        prompt=prompt, schema=SCHEMA, system=SYSTEM, on_success=on_success,
        fallback={"market_expectations": {"expected_skills": [], "country_notes": "", "industry_trends": []}},
        log=log,
    )
