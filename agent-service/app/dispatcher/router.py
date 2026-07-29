"""FastAPI route for generic workflow dispatch — `POST /workflows/{workflow_id}/runs`.

Additive alongside `/runs` and `/skill-gap/runs`, not a replacement for either. Standardizes the
response envelope every dispatched workflow returns: generic execution metadata (workflowId,
executionId, correlationId, status, durationMs) plus an opaque, workflow-specific `output` payload
— the Java Control Plane already models this exact shape as `WorkflowExecutionResult`/
`WorkflowExecutorOutcome` (`ai.careerpilot.runtime`), so this endpoint is that record's Python
counterpart. Never raises past this handler — an unknown `workflow_id` is a structured 404; any
other failure (including the graph itself raising) degrades to `status="error"`, matching every
other route in this service.
"""
from __future__ import annotations

import logging
import time
import uuid
from typing import Any

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from .registry import WorkflowNotRegisteredError, get_dispatch_registry

log = logging.getLogger(__name__)

router = APIRouter(prefix="/workflows", tags=["workflow-dispatcher"])


class WorkflowRunRequest(BaseModel):
    mission_id: str
    user_id: str
    execution_id: str = ""
    correlation_id: str = ""
    inputs: dict[str, Any] = Field(default_factory=dict)


class WorkflowRunResponse(BaseModel):
    workflowId: str
    executionId: str
    correlationId: str
    status: str  # "completed" | "error"
    durationMs: int
    output: dict[str, Any]
    errors: list[str]


@router.get("")
def list_registered_workflows() -> dict[str, list[str]]:
    return {"workflowIds": get_dispatch_registry().list_ids()}


@router.post("/{workflow_id}/runs", response_model=WorkflowRunResponse)
def dispatch_run(workflow_id: str, req: WorkflowRunRequest) -> WorkflowRunResponse:
    execution_id = req.execution_id or str(uuid.uuid4())
    correlation_id = req.correlation_id or str(uuid.uuid4())

    try:
        registration = get_dispatch_registry().get(workflow_id)
    except WorkflowNotRegisteredError:
        log.warning(
            "workflow_dispatch_unknown_workflow",
            extra={
                "event": "workflow_dispatch_unknown_workflow",
                "workflow_id": workflow_id,
                "execution_id": execution_id,
                "correlation_id": correlation_id,
            },
        )
        raise HTTPException(status_code=404, detail=f"Unknown workflow_id: {workflow_id}")

    log.info(
        "workflow_dispatch_started",
        extra={
            "event": "workflow_dispatch_started",
            "workflow_id": workflow_id,
            "mission_id": req.mission_id,
            "execution_id": execution_id,
            "correlation_id": correlation_id,
        },
    )
    t0 = time.monotonic()
    try:
        initial_state = registration.state_mapper(req, execution_id, correlation_id)
        graph = registration.graph_factory()
        final_state = graph.invoke(initial_state)
    except Exception as e:  # noqa: BLE001
        duration_ms = int((time.monotonic() - t0) * 1000)
        log.error(
            "workflow_dispatch_failed",
            extra={
                "event": "workflow_dispatch_failed",
                "workflow_id": workflow_id,
                "mission_id": req.mission_id,
                "execution_id": execution_id,
                "correlation_id": correlation_id,
                "duration_ms": duration_ms,
                "exception_type": type(e).__name__,
                "exception_msg": str(e),
            },
            exc_info=True,
        )
        return WorkflowRunResponse(
            workflowId=workflow_id, executionId=execution_id, correlationId=correlation_id,
            status="error", durationMs=duration_ms, output={}, errors=[f"{type(e).__name__}: {e}"],
        )

    duration_ms = int((time.monotonic() - t0) * 1000)
    errors = final_state.get(registration.error_key) or []
    status = "error" if errors else "completed"
    output = registration.output_mapper(final_state)

    log.info(
        "workflow_dispatch_completed",
        extra={
            "event": "workflow_dispatch_completed",
            "workflow_id": workflow_id,
            "mission_id": req.mission_id,
            "execution_id": execution_id,
            "correlation_id": correlation_id,
            "status": status,
            "duration_ms": duration_ms,
            "error_count": len(errors),
        },
    )
    return WorkflowRunResponse(
        workflowId=workflow_id, executionId=execution_id, correlationId=correlation_id,
        status=status, durationMs=duration_ms, output=output, errors=errors,
    )
