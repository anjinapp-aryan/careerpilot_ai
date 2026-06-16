"""FastAPI entrypoint for the LangGraph agent service."""
from __future__ import annotations

import logging
import uuid
from typing import Any

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from .config import settings
from .graph import get_compiled_graph

logging.basicConfig(level=getattr(logging, settings.log_level, logging.INFO))
log = logging.getLogger("careerpilot.agent")

app = FastAPI(title="CareerPilot Agent Service", version="0.1.0")


class JobDescriptionDTO(BaseModel):
    id: str
    title: str
    company: str
    description: str
    location: str | None = None
    salary: str | None = None


class StartRunRequest(BaseModel):
    user_id: str
    org_id: str
    resume_text: str
    target_role: str
    target_seniority: str = ""
    target_locations: list[str] = Field(default_factory=list)
    job_descriptions: list[JobDescriptionDTO] = Field(default_factory=list)
    thread_id: str | None = None


class ResumeRunRequest(BaseModel):
    thread_id: str
    human_decision: str  # "approved" | "rejected"
    human_feedback: str = ""


class RunResponse(BaseModel):
    thread_id: str
    status: str  # "completed" | "interrupted" | "error"
    state: dict[str, Any]


def _config(thread_id: str) -> dict:
    return {"configurable": {"thread_id": thread_id}}


def _classify(state: dict[str, Any]) -> str:
    if state.get("awaiting_human_approval") and not state.get("human_decision"):
        return "interrupted"
    if state.get("errors"):
        return "error"
    return "completed"


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "provider": settings.ai_provider, "model": settings.ai_model}


@app.post("/runs", response_model=RunResponse)
def start_run(req: StartRunRequest) -> RunResponse:
    thread_id = req.thread_id or str(uuid.uuid4())
    graph = get_compiled_graph()
    initial: dict[str, Any] = {
        "user_id": req.user_id,
        "org_id": req.org_id,
        "resume_text": req.resume_text,
        "target_role": req.target_role,
        "target_seniority": req.target_seniority,
        "target_locations": req.target_locations,
        "job_descriptions": [j.model_dump() for j in req.job_descriptions],
        "awaiting_human_approval": True,
        "human_decision": "",
        "errors": [],
        "cost_usd": 0.0,
    }
    try:
        final_state = graph.invoke(initial, config=_config(thread_id))
    except Exception as e:  # noqa: BLE001
        # NodeInterrupt and other interrupts surface here; fetch the snapshot.
        snapshot = graph.get_state(_config(thread_id))
        if snapshot and snapshot.next:
            return RunResponse(thread_id=thread_id, status="interrupted", state=snapshot.values)
        log.exception("run failed")
        raise HTTPException(status_code=500, detail=str(e))
    return RunResponse(thread_id=thread_id, status=_classify(final_state), state=final_state)


@app.post("/runs/resume", response_model=RunResponse)
def resume_run(req: ResumeRunRequest) -> RunResponse:
    graph = get_compiled_graph()
    cfg = _config(req.thread_id)
    snapshot = graph.get_state(cfg)
    if not snapshot:
        raise HTTPException(status_code=404, detail="thread not found")

    graph.update_state(
        cfg,
        {
            "human_decision": req.human_decision,
            "human_feedback": req.human_feedback,
            "awaiting_human_approval": False,
        },
    )
    final_state = graph.invoke(None, config=cfg)
    return RunResponse(thread_id=req.thread_id, status=_classify(final_state), state=final_state)


@app.get("/runs/{thread_id}", response_model=RunResponse)
def get_run(thread_id: str) -> RunResponse:
    graph = get_compiled_graph()
    snapshot = graph.get_state(_config(thread_id))
    if not snapshot:
        raise HTTPException(status_code=404, detail="thread not found")
    status = "interrupted" if snapshot.next else _classify(snapshot.values)
    return RunResponse(thread_id=thread_id, status=status, state=snapshot.values)
