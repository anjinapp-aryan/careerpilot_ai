"""Salary Intelligence Agent — benchmarks and negotiation strategy."""
from __future__ import annotations

import json
import logging

from ..ai_provider import get_ai_provider
from ..state import CareerState

log = logging.getLogger(__name__)

SYSTEM = "You are a compensation analyst for the global tech market. Output strict JSON only."

SCHEMA = {
    "type": "object",
    "properties": {
        "salary_insights": {
            "type": "object",
            "properties": {
                "currency": {"type": "string"},
                "p25": {"type": "integer"},
                "p50": {"type": "integer"},
                "p75": {"type": "integer"},
                "p90": {"type": "integer"},
                "negotiation_strategy": {"type": "array", "items": {"type": "string"}},
                "leverage_points": {"type": "array", "items": {"type": "string"}},
            },
            "required": ["currency", "p25", "p50", "p75", "p90", "negotiation_strategy"],
        }
    },
    "required": ["salary_insights"],
}


def salary_intelligence_node(state: CareerState) -> dict:
    profile = state.get("candidate_profile") or {}
    prompt = (
        "Estimate total comp percentiles (annual, base+bonus+equity grant year-1) for the role and locations. "
        "Provide a negotiation_strategy (5-7 bullets) and leverage_points (3-5 bullets) tailored to the candidate.\n\n"
        f"PROFILE: {json.dumps(profile)}\n"
        f"TARGET_ROLE: {state.get('target_role', '')}\n"
        f"SENIORITY: {state.get('target_seniority', '')}\n"
        f"LOCATIONS: {json.dumps(state.get('target_locations', []))}"
    )
    try:
        result = get_ai_provider().generate_structured_response(prompt, SCHEMA, system=SYSTEM)
    except Exception as e:  # noqa: BLE001
        log.exception("salary_intelligence failed")
        return {"errors": [f"salary_intelligence: {e}"]}

    return {"salary_insights": result.get("salary_insights", {})}
