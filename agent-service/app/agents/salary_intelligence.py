"""Salary Intelligence Agent — benchmarks and negotiation strategy."""
from __future__ import annotations

import json
import logging

from ..workflow_ai_gateway import get_workflow_ai_gateway
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
        log.info(
            "agent_stage_entry",
            extra={
                "event": "agent_stage_entry",
                "agent": "salary_intelligence",
                "profile_keys": list(profile.keys()),
            },
        )
        gateway = get_workflow_ai_gateway()
        log.info(
            "agent_gateway_obtained",
            extra={
                "event": "agent_gateway_obtained",
                "agent": "salary_intelligence",
            },
        )
        result = gateway.generate_structured_response(
            prompt, SCHEMA, system=SYSTEM, stage="salary_intelligence"
        )
        log.info(
            "agent_provider_response_received",
            extra={
                "event": "agent_provider_response_received",
                "agent": "salary_intelligence",
                "result_type": type(result).__name__,
                "result_keys": list(result.keys()) if isinstance(result, dict) else "not_dict",
            },
        )
        salary_insights = result.get("salary_insights", {})
        log.info(
            "agent_dto_created",
            extra={
                "event": "agent_dto_created",
                "agent": "salary_intelligence",
                "insights_type": type(salary_insights).__name__,
                "insights_keys": list(salary_insights.keys()) if isinstance(salary_insights, dict) else "not_dict",
            },
        )
        response = {"salary_insights": salary_insights}
        log.info(
            "agent_response_serialized",
            extra={
                "event": "agent_response_serialized",
                "agent": "salary_intelligence",
                "response_keys": list(response.keys()),
            },
        )
        return response
    except Exception as e:  # noqa: BLE001
        log.error(
            "agent_stage_failed",
            extra={
                "event": "agent_stage_failed",
                "agent": "salary_intelligence",
                "exception_type": type(e).__name__,
                "exception_msg": str(e),
            },
            exc_info=True,
        )
        error_response = {"errors": [f"salary_intelligence: {e}"], "salary_insights": {}}
        log.info(
            "agent_error_response_created",
            extra={
                "event": "agent_error_response_created",
                "agent": "salary_intelligence",
                "error_count": len(error_response.get("errors", [])),
            },
        )
        return error_response
