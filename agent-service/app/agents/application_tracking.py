"""Application Tracking Agent — produces the application record + reminder schedule."""
from __future__ import annotations

import json
import logging
from datetime import datetime, timedelta, timezone

from ..ai_provider import get_ai_provider
from ..state import CareerState

log = logging.getLogger(__name__)

SYSTEM = "You generate concise, structured application-tracking artifacts. Output strict JSON only."

SCHEMA = {
    "type": "object",
    "properties": {
        "tracked_application": {
            "type": "object",
            "properties": {
                "primary_job_id": {"type": "string"},
                "status": {"type": "string"},
                "next_actions": {"type": "array", "items": {"type": "string"}},
                "reminders": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "when_iso": {"type": "string"},
                            "message": {"type": "string"},
                        },
                        "required": ["when_iso", "message"],
                    },
                },
            },
            "required": ["primary_job_id", "status", "next_actions", "reminders"],
        }
    },
    "required": ["tracked_application"],
}


def application_tracking_node(state: CareerState) -> dict:
    ranked = state.get("ranked_jobs") or []
    if not ranked:
        return {"tracked_application": {}}

    now = datetime.now(timezone.utc)
    horizon = (now + timedelta(days=14)).isoformat()

    prompt = (
        "Generate a tracking record for the top job. status should be one of "
        "[saved, applied, interviewing, offer, rejected]. Provide 3-5 next_actions and "
        "3-5 reminders with ISO timestamps before " + horizon + ".\n\n"
        f"TOP_JOB_ID: {ranked[0]['job_id']}\n"
        f"HUMAN_DECISION: {state.get('human_decision', '')}\n"
        f"NOW: {now.isoformat()}"
    )
    try:
        result = get_ai_provider().generate_structured_response(prompt, SCHEMA, system=SYSTEM)
    except Exception as e:  # noqa: BLE001
        log.exception("application_tracking failed")
        return {"errors": [f"application_tracking: {e}"]}

    return {"tracked_application": result.get("tracked_application", {})}
