"""ATS Optimization Agent — compares resume vs JD, finds missing keywords."""
from __future__ import annotations

import json
import logging

from ..workflow_ai_gateway import get_workflow_ai_gateway
from ..state import CareerState

log = logging.getLogger(__name__)

SYSTEM = (
    "You are an ATS (Applicant Tracking System) optimization expert. You analyze resumes "
    "against job descriptions and recommend concrete, surgical changes. Output strict JSON."
)

SCHEMA = {
    "type": "object",
    "properties": {
        "ats_score": {"type": "integer"},
        "missing_keywords": {"type": "array", "items": {"type": "string"}},
        "ats_optimization_plan": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["ats_score", "missing_keywords", "ats_optimization_plan"],
}


def ats_optimization_node(state: CareerState) -> dict:
    resume = state.get("resume_text", "")
    ranked = state.get("ranked_jobs") or []
    jobs = state.get("job_descriptions") or []
    if not resume or not jobs:
        log.warning("ats_optimization: missing resume or jobs")
        return {"ats_score": 0, "missing_keywords": [], "ats_optimization_plan": []}

    # Use the top-ranked job as the optimization target.
    target_id = ranked[0]["job_id"] if ranked else jobs[0]["id"]
    target = next((j for j in jobs if str(j.get("id")) == str(target_id)), jobs[0])

    prompt = (
        "Score the resume's ATS compatibility against the target JD (0-100). "
        "List missing_keywords that would meaningfully improve ATS matching. "
        "Return a numbered ats_optimization_plan of 5-8 concrete edits.\n\n"
        f"RESUME:\n{resume}\n\n"
        f"TARGET_JOB:\n{json.dumps(target)}"
    )
    try:
        log.info("ats_optimization: stage started")
        gateway = get_workflow_ai_gateway()
        result = gateway.generate_structured_response(prompt, SCHEMA, system=SYSTEM, stage="ats_optimization")
        log.info("ats_optimization: stage completed successfully")
        return {
            "ats_score": int(result.get("ats_score", 0)),
            "missing_keywords": result.get("missing_keywords", []),
            "ats_optimization_plan": result.get("ats_optimization_plan", []),
        }
    except Exception as e:  # noqa: BLE001
        log.error("ats_optimization: stage failed", extra={"error": str(e)}, exc_info=True)
        return {"errors": [f"ats_optimization: {e}"], "ats_score": 0, "missing_keywords": [], "ats_optimization_plan": []}
