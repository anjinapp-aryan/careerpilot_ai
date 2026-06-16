"""Interview Preparation Agent — generates a tailored interview prep plan."""
from __future__ import annotations

import json
import logging

from ..ai_provider import get_ai_provider
from ..state import CareerState

log = logging.getLogger(__name__)

SYSTEM = (
    "You are a senior interview coach for software engineering, cloud, DevOps and engineering leadership roles. "
    "You output strict JSON only."
)

SCHEMA = {
    "type": "object",
    "properties": {
        "interview_plan": {
            "type": "object",
            "properties": {
                "technical_questions": {"type": "array", "items": {"type": "string"}},
                "behavioral_questions": {"type": "array", "items": {"type": "string"}},
                "leadership_questions": {"type": "array", "items": {"type": "string"}},
                "system_design_questions": {"type": "array", "items": {"type": "string"}},
                "study_topics": {"type": "array", "items": {"type": "string"}},
            },
            "required": ["technical_questions", "behavioral_questions", "system_design_questions"],
        },
        "interview_readiness_score": {"type": "integer"},
    },
    "required": ["interview_plan", "interview_readiness_score"],
}


def interview_prep_node(state: CareerState) -> dict:
    profile = state.get("candidate_profile") or {}
    ranked = state.get("ranked_jobs") or []
    jobs = state.get("job_descriptions") or []
    target = next((j for j in jobs if ranked and str(j.get("id")) == str(ranked[0]["job_id"])), jobs[0] if jobs else {})

    prompt = (
        "Generate an interview prep plan tailored to the candidate and target role. "
        "Include 8-10 technical, 5 behavioral, 4 leadership, and 3 system design questions. "
        "Then provide interview_readiness_score (0-100) based on resume-vs-role fit.\n\n"
        f"PROFILE:\n{json.dumps(profile)}\n\n"
        f"TARGET_JOB:\n{json.dumps(target)}"
    )
    try:
        result = get_ai_provider().generate_structured_response(prompt, SCHEMA, system=SYSTEM)
    except Exception as e:  # noqa: BLE001
        log.exception("interview_prep failed")
        return {"errors": [f"interview_prep: {e}"]}

    return {
        "interview_plan": result.get("interview_plan", {}),
        "interview_readiness_score": int(result.get("interview_readiness_score", 0)),
    }
