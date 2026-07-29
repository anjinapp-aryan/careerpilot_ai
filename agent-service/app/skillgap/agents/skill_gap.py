"""Skill Gap Agent — compares the candidate's profile against market expectations.

Deliberately deterministic: the Market Intelligence Agent already assigned each expected skill a
priority and a reason (the judgment call); this node is a pure set-difference plus a bucket sort —
no LLM call, fully reproducible/testable, and free (no AI Gateway cost) for every run.
"""
from __future__ import annotations

import logging

from ..state import SkillGapState

log = logging.getLogger(__name__)


def _normalize(name: str) -> str:
    return (name or "").strip().lower()


def skill_gap_node(state: SkillGapState) -> dict:
    profile = state.get("candidate_profile") or {}
    known = {_normalize(s) for s in (profile.get("skills") or []) + (profile.get("technologies") or [])}
    expected = (state.get("market_expectations") or {}).get("expected_skills") or []

    critical: list[dict] = []
    important: list[dict] = []
    optional: list[dict] = []

    for item in expected:
        skill = (item.get("skill") or "").strip()
        if not skill or _normalize(skill) in known:
            continue
        priority = (item.get("priority") or "IMPORTANT").upper()
        gap = {"skill": skill, "priority": priority, "reason": item.get("reason", "")}
        if priority == "CRITICAL":
            critical.append(gap)
        elif priority == "OPTIONAL":
            optional.append(gap)
        else:
            important.append(gap)

    log.info(
        "skill_gap: computed",
        extra={"critical": len(critical), "important": len(important), "optional": len(optional)},
    )
    return {
        "critical_skill_gaps": critical,
        "important_skill_gaps": important,
        "optional_skill_gaps": optional,
    }
