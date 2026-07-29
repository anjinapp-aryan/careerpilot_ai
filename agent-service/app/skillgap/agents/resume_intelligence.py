"""Resume Intelligence Agent — extracts skills, experience, certifications, technologies, strengths.

Distinct from `app.agents.resume_intelligence` (the main career graph's node): this agent serves
the Skill Gap Workflow's narrower input shape and is not shared code, by design — the two graphs
are independent (see `app/skillgap/__init__.py`). When no raw `resume_text` is supplied (the
candidate hasn't uploaded a resume, only structured mission skills), this agent builds the profile
deterministically from `current_skills`/`experience_years` rather than inventing resume content —
never fabricate a signal that wasn't provided.
"""
from __future__ import annotations

import json
import logging

from ..state import SkillGapState
from ...agent_support import call_structured_agent

log = logging.getLogger(__name__)

SYSTEM = "You are a precise technical resume analyst. Output strict JSON only."

SCHEMA = {
    "type": "object",
    "properties": {
        "skills": {"type": "array", "items": {"type": "string"}},
        "technologies": {"type": "array", "items": {"type": "string"}},
        "certifications": {"type": "array", "items": {"type": "string"}},
        "experience_years": {"type": "integer"},
        "strengths": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["skills", "technologies", "certifications", "experience_years", "strengths"],
}


def resume_intelligence_node(state: SkillGapState) -> dict:
    resume_text = (state.get("resume_text") or "").strip()
    current_skills = state.get("current_skills") or []
    experience_years = state.get("experience_years") or 0

    if not resume_text:
        profile = {
            "skills": current_skills,
            "technologies": current_skills,
            "certifications": [],
            "experience_years": experience_years,
            "strengths": current_skills[:3],
        }
        log.info("resume_intelligence: deterministic profile (no resume_text supplied)")
        return {"candidate_profile": profile}

    prompt = (
        "Extract a structured candidate profile from this resume text. List distinct skills, "
        "technologies, certifications, total years of professional experience, and the "
        "candidate's top strengths.\n\n"
        f"RESUME:\n{resume_text}\n\n"
        f"KNOWN_SKILLS_HINT: {json.dumps(current_skills)}"
    )
    def on_success(result: dict) -> dict:
        return {"candidate_profile": {
            "skills": result.get("skills") or current_skills,
            "technologies": result.get("technologies") or [],
            "certifications": result.get("certifications") or [],
            "experience_years": result.get("experience_years") or experience_years,
            "strengths": result.get("strengths") or [],
        }}

    return call_structured_agent(
        stage="skill_gap_resume_intelligence", log_name="resume_intelligence",
        prompt=prompt, schema=SCHEMA, system=SYSTEM, on_success=on_success,
        fallback={"candidate_profile": {
            "skills": current_skills, "technologies": current_skills, "certifications": [],
            "experience_years": experience_years, "strengths": [],
        }},
        log=log,
    )
