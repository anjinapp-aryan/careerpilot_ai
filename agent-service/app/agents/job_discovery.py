"""Job Discovery Agent — ranks supplied jobs against the candidate profile."""
from __future__ import annotations

import json
import logging

from ..workflow_ai_gateway import get_workflow_ai_gateway
from ..state import CareerState

log = logging.getLogger(__name__)

SYSTEM = (
    "You are a job-market matching engine for software engineering, cloud, and DevOps roles. "
    "You output strict JSON only. Scores are integers 0-100."
)

SCHEMA = {
    "type": "object",
    "properties": {
        "ranked_jobs": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "job_id": {"type": "string"},
                    "match_score": {"type": "integer"},
                    "missing_skills": {"type": "array", "items": {"type": "string"}},
                    "rationale": {"type": "string"},
                },
                "required": ["job_id", "match_score", "missing_skills", "rationale"],
            },
        },
        "job_match_score": {"type": "integer"},
    },
    "required": ["ranked_jobs", "job_match_score"],
}


def job_discovery_node(state: CareerState) -> dict:
    profile = state.get("candidate_profile") or {}
    skills = state.get("extracted_skills") or []
    jobs = state.get("job_descriptions") or []
    if not jobs:
        log.warning("job_discovery: no jobs provided")
        return {"ranked_jobs": [], "job_match_score": 0}

    prompt = (
        "Rank these jobs for the candidate. For each, compute match_score (0-100), list "
        "missing_skills the candidate would need to acquire, and a 1-2 sentence rationale. "
        "Then return the overall job_match_score = max(match_score) across the set.\n\n"
        f"CANDIDATE_PROFILE:\n{json.dumps(profile)}\n\n"
        f"CANDIDATE_SKILLS:\n{json.dumps(skills)}\n\n"
        f"TARGET_ROLE: {state.get('target_role', '')}\n"
        f"TARGET_SENIORITY: {state.get('target_seniority', '')}\n"
        f"LOCATIONS: {json.dumps(state.get('target_locations', []))}\n\n"
        f"JOBS:\n{json.dumps(jobs)}"
    )
    try:
        log.info("job_discovery: stage started")
        gateway = get_workflow_ai_gateway()
        result = gateway.generate_structured_response(prompt, SCHEMA, system=SYSTEM, stage="job_discovery")
        log.info("job_discovery: stage completed successfully")
        return {
            "ranked_jobs": result.get("ranked_jobs", []),
            "job_match_score": int(result.get("job_match_score", 0)),
        }
    except Exception as e:  # noqa: BLE001
        log.error("job_discovery: stage failed", extra={"error": str(e)}, exc_info=True)
        return {"errors": [f"job_discovery: {e}"], "ranked_jobs": [], "job_match_score": 0}
