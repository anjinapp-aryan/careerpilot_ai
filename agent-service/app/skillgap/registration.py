"""Registers the Skill Gap Intelligence graph into the generic dispatcher (Phase 10A).

Purely additive: `app/skillgap/router.py`'s existing `POST /skill-gap/runs` is untouched and keeps
working exactly as before. This registration makes the same underlying graph ALSO reachable at
`POST /workflows/SKILL_GAP_INTELLIGENCE_V1/runs` — proving the generic dispatch path works using
Skill Gap as the reference workflow, without modifying Skill Gap's own behavior in any way.
"""
from __future__ import annotations

from typing import Any

from .graph import get_compiled_skill_gap_graph
from .router import SkillGapRunRequest
from .state import SkillGapState
from ..dispatcher.registry import WorkflowDispatchRegistry, WorkflowRegistration

WORKFLOW_ID = "SKILL_GAP_INTELLIGENCE_V1"

_OUTPUT_FIELDS = (
    "readiness_score", "confidence", "critical_skill_gaps", "important_skill_gaps",
    "optional_skill_gaps", "recommended_learning_roadmap", "estimated_completion_months",
    "mission_progress", "strengths", "risks", "recommendations",
)


def _state_mapper(req: Any, execution_id: str, correlation_id: str) -> SkillGapState:
    """Maps the generic `WorkflowRunRequest.inputs` (opaque to the dispatcher) into `SkillGapState`.

    Reuses `SkillGapRunRequest` purely for its field defaults/validation — the same shape
    `/skill-gap/runs` already validates — rather than duplicating that logic here.
    """
    inputs = req.inputs or {}
    parsed = SkillGapRunRequest(mission_id=req.mission_id, user_id=req.user_id, **{
        k: v for k, v in inputs.items() if k in SkillGapRunRequest.model_fields and k not in ("mission_id", "user_id")
    })
    return {
        "mission_id": parsed.mission_id,
        "user_id": parsed.user_id,
        "workflow_id": WORKFLOW_ID,
        "execution_id": execution_id,
        "correlation_id": correlation_id,
        "mission_statement": parsed.mission_statement,
        "target_role": parsed.target_role,
        "target_level": parsed.target_level,
        "target_countries": parsed.target_countries,
        "timeline_months": parsed.timeline_months,
        "current_skills": parsed.current_skills,
        "skills_to_acquire": parsed.skills_to_acquire,
        "experience_years": parsed.experience_years,
        "resume_text": parsed.resume_text,
        "errors": [],
    }


def _output_mapper(final_state: dict) -> dict:
    return {field: final_state.get(field) for field in _OUTPUT_FIELDS}


def register_skill_gap_workflow(registry: WorkflowDispatchRegistry) -> None:
    registry.register(WorkflowRegistration(
        workflow_id=WORKFLOW_ID,
        graph_factory=get_compiled_skill_gap_graph,
        state_mapper=_state_mapper,
        output_mapper=_output_mapper,
    ))
