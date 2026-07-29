"""Mission Context Agent — structures the already-supplied mission fields.

Deliberately deterministic (no AI call): the Java Control Plane already sends target role, level,
countries, and timeline as structured fields (read straight from `CareerMission` — nothing here is
inferred). Calling an LLM to restate data that already exists in a structured form would be a
wasted round trip, not "understanding the mission" — same "deterministic where the data already
exists" discipline as `StrategyEvaluationService`/`CountryMatchingCapability` on the Java side.
"""
from __future__ import annotations

import logging

from ..state import SkillGapState

log = logging.getLogger(__name__)


def mission_context_node(state: SkillGapState) -> dict:
    target_role = (state.get("target_role") or "").strip()
    target_level = (state.get("target_level") or "").strip()
    target_countries = state.get("target_countries") or []
    timeline_months = state.get("timeline_months")
    mission_statement = (state.get("mission_statement") or "").strip()

    if mission_statement:
        summary = mission_statement
    else:
        role_desc = f"{target_level} {target_role}".strip() if target_level else target_role
        summary = f"Become a {role_desc}" if role_desc else "Advance the candidate's career mission"
    if target_countries:
        summary = f"{summary} in {', '.join(target_countries)}"
    if timeline_months:
        summary = f"{summary} within {timeline_months} months"

    mission_context = {
        "target_role": target_role,
        "target_level": target_level,
        "target_countries": target_countries,
        "timeline_months": timeline_months,
        "mission_summary": summary,
    }
    log.info(
        "mission_context: stage completed",
        extra={"target_role": target_role, "target_countries": target_countries, "timeline_months": timeline_months},
    )
    return {"mission_context": mission_context}
