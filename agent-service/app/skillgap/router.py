"""FastAPI route for the Skill Gap Intelligence Workflow — additive, mounted separately from `/runs`.

`POST /skill-gap/runs` is the entire HTTP contract. It never raises past this handler: any
exception during graph execution is caught, logged, and returned as a structured `errors`-populated
response with `status="error"`, mirroring `app/main.py`'s own `/runs` discipline (the two endpoints
share this convention deliberately, but share no code — see `app/skillgap/__init__.py`).
"""
from __future__ import annotations

import logging
import time
import uuid
from typing import Any

from fastapi import APIRouter
from pydantic import BaseModel, Field

from .graph import get_compiled_skill_gap_graph
from .state import SkillGapState

log = logging.getLogger(__name__)

router = APIRouter(prefix="/skill-gap", tags=["skill-gap"])


class SkillGapRunRequest(BaseModel):
    mission_id: str
    user_id: str
    workflow_id: str = ""
    execution_id: str = ""
    correlation_id: str = ""
    mission_statement: str = ""
    target_role: str
    target_level: str = ""
    target_countries: list[str] = Field(default_factory=list)
    timeline_months: int | None = None
    current_skills: list[str] = Field(default_factory=list)
    skills_to_acquire: list[str] = Field(default_factory=list)
    experience_years: int | None = None
    resume_text: str = ""


class SkillGapRunResponse(BaseModel):
    missionId: str
    workflowId: str
    executionId: str
    status: str  # "completed" | "error"
    readinessScore: int
    confidence: float
    criticalSkillGaps: list[dict[str, Any]]
    importantSkillGaps: list[dict[str, Any]]
    optionalSkillGaps: list[dict[str, Any]]
    recommendedLearningRoadmap: list[dict[str, Any]]
    estimatedCompletionMonths: int
    missionProgress: float
    strengths: list[str]
    risks: list[str]
    recommendations: list[str]
    errors: list[str]


def _empty_response(mission_id: str, workflow_id: str, execution_id: str, status: str, errors: list[str]) -> SkillGapRunResponse:
    return SkillGapRunResponse(
        missionId=mission_id, workflowId=workflow_id, executionId=execution_id, status=status,
        readinessScore=0, confidence=0.0, criticalSkillGaps=[], importantSkillGaps=[], optionalSkillGaps=[],
        recommendedLearningRoadmap=[], estimatedCompletionMonths=0, missionProgress=0.0,
        strengths=[], risks=[], recommendations=[], errors=errors,
    )


@router.post("/runs", response_model=SkillGapRunResponse)
def start_skill_gap_run(req: SkillGapRunRequest) -> SkillGapRunResponse:
    execution_id = req.execution_id or str(uuid.uuid4())
    initial: SkillGapState = {
        "mission_id": req.mission_id,
        "user_id": req.user_id,
        "workflow_id": req.workflow_id,
        "execution_id": execution_id,
        "correlation_id": req.correlation_id,
        "mission_statement": req.mission_statement,
        "target_role": req.target_role,
        "target_level": req.target_level,
        "target_countries": req.target_countries,
        "timeline_months": req.timeline_months,
        "current_skills": req.current_skills,
        "skills_to_acquire": req.skills_to_acquire,
        "experience_years": req.experience_years,
        "resume_text": req.resume_text,
        "errors": [],
    }

    log.info(
        "skill_gap_run_started",
        extra={
            "event": "skill_gap_run_started",
            "execution_id": execution_id,
            "mission_id": req.mission_id,
            "correlation_id": req.correlation_id,
            "target_role": req.target_role,
        },
    )
    t0 = time.monotonic()
    try:
        graph = get_compiled_skill_gap_graph()
        final_state = graph.invoke(initial)
    except Exception as e:  # noqa: BLE001
        log.error(
            "skill_gap_run_failed",
            extra={
                "event": "skill_gap_run_failed",
                "execution_id": execution_id,
                "correlation_id": req.correlation_id,
                "exception_type": type(e).__name__,
                "exception_msg": str(e),
            },
            exc_info=True,
        )
        return _empty_response(
            req.mission_id, req.workflow_id, execution_id, "error",
            [f"skill_gap_run: {type(e).__name__}: {e}"],
        )

    duration_ms = int((time.monotonic() - t0) * 1000)
    errors = final_state.get("errors") or []
    status = "error" if errors else "completed"
    log.info(
        "skill_gap_run_completed",
        extra={
            "event": "skill_gap_run_completed",
            "execution_id": execution_id,
            "correlation_id": req.correlation_id,
            "status": status,
            "duration_ms": duration_ms,
            "readiness_score": final_state.get("readiness_score", 0),
            "error_count": len(errors),
        },
    )
    return SkillGapRunResponse(
        missionId=req.mission_id,
        workflowId=req.workflow_id,
        executionId=execution_id,
        status=status,
        readinessScore=final_state.get("readiness_score", 0),
        confidence=final_state.get("confidence", 0.0),
        criticalSkillGaps=final_state.get("critical_skill_gaps", []),
        importantSkillGaps=final_state.get("important_skill_gaps", []),
        optionalSkillGaps=final_state.get("optional_skill_gaps", []),
        recommendedLearningRoadmap=final_state.get("recommended_learning_roadmap", []),
        estimatedCompletionMonths=final_state.get("estimated_completion_months", 0),
        missionProgress=final_state.get("mission_progress", 0.0),
        strengths=final_state.get("strengths", []),
        risks=final_state.get("risks", []),
        recommendations=final_state.get("recommendations", []),
        errors=errors,
    )
