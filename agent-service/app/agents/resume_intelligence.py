"""Resume Intelligence Agent — parses resume, extracts skills/achievements, builds profile."""
from __future__ import annotations

import logging

from ..ai_provider import get_ai_provider
from ..state import CareerState

log = logging.getLogger(__name__)

SYSTEM = (
    "You are a senior technical recruiter and resume analyst specializing in software engineering, "
    "cloud, and DevOps roles. You output strict JSON only."
)

SCHEMA = {
    "type": "object",
    "properties": {
        "candidate_profile": {
            "type": "object",
            "properties": {
                "summary": {"type": "string"},
                "years_experience": {"type": "integer"},
                "current_title": {"type": "string"},
                "seniority": {"type": "string"},
                "top_strengths": {"type": "array", "items": {"type": "string"}},
                "achievements": {"type": "array", "items": {"type": "string"}},
                "domains": {"type": "array", "items": {"type": "string"}},
            },
            "required": ["summary", "years_experience", "seniority"],
        },
        "extracted_skills": {"type": "array", "items": {"type": "string"}},
        "resume_score": {"type": "integer"},
    },
    "required": ["candidate_profile", "extracted_skills", "resume_score"],
}


def resume_intelligence_node(state: CareerState) -> dict:
    resume = state.get("resume_text", "")
    if not resume.strip():
        return {"errors": ["resume_text is empty"], "resume_score": 0, "extracted_skills": [], "candidate_profile": {}}

    prompt = (
        "Analyze the following resume. Extract a candidate profile, a flat list of "
        "technical skills (canonical names, lowercase where customary, e.g. 'kubernetes', 'spring boot'), "
        "and a resume_score from 0-100 reflecting clarity, impact, quantification, and seniority signal.\n\n"
        f"RESUME:\n{resume}"
    )
    try:
        result = get_ai_provider().generate_structured_response(prompt, SCHEMA, system=SYSTEM)
    except Exception as e:  # noqa: BLE001
        log.exception("resume_intelligence failed")
        return {"errors": [f"resume_intelligence: {e}"]}

    return {
        "candidate_profile": result.get("candidate_profile", {}),
        "extracted_skills": result.get("extracted_skills", []),
        "resume_score": int(result.get("resume_score", 0)),
    }
