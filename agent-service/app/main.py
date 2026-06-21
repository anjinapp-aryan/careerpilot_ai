"""FastAPI entrypoint for the LangGraph agent service."""
from __future__ import annotations

import logging
import logging.config
import uuid
from typing import Any

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from .config import settings
from .graph import get_compiled_graph
from .rate_limiter import GeminiRateLimiter

# ---------------------------------------------------------------------------
# Structured JSON logging — wire up python-json-logger before anything else
# ---------------------------------------------------------------------------

def _configure_logging(level: str) -> None:
    """
    Configure the root logger to emit JSON lines.
    All log.info("event_name", extra={...}) calls will include structured fields.
    """
    logging.config.dictConfig({
        "version": 1,
        "disable_existing_loggers": False,
        "formatters": {
            "json": {
                "()": "pythonjsonlogger.jsonlogger.JsonFormatter",
                "fmt": "%(asctime)s %(name)s %(levelname)s %(message)s",
                "datefmt": "%Y-%m-%dT%H:%M:%SZ",
            }
        },
        "handlers": {
            "stdout": {
                "class": "logging.StreamHandler",
                "stream": "ext://sys.stdout",
                "formatter": "json",
            }
        },
        "root": {
            "handlers": ["stdout"],
            "level": level.upper(),
        },
    })


_configure_logging(settings.log_level)
log = logging.getLogger("careerpilot.agent")

app = FastAPI(title="CareerPilot Agent Service", version="0.1.0")

# Exact origins only, driven by CORS_ALLOWED_ORIGINS — no wildcards. The agent
# service is normally only called server-to-server by the backend, but the
# interactive /docs page and any direct browser testing need this.
app.add_middleware(
    CORSMiddleware,
    allow_origins=[o.strip() for o in settings.cors_allowed_origins.split(",") if o.strip()],
    allow_credentials=True,
    allow_methods=["GET", "POST", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type", "Accept"],
)


# ---------------------------------------------------------------------------
# Request/response models
# ---------------------------------------------------------------------------

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


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _config(thread_id: str) -> dict:
    return {"configurable": {"thread_id": thread_id}}


def _classify(state: dict[str, Any]) -> str:
    if state.get("awaiting_human_approval") and not state.get("human_decision"):
        return "interrupted"
    # A rejected run terminates at the approval gate (no application_tracking);
    # surface it as its own terminal status so the UI can show REJECTED.
    if (state.get("human_decision") or "").strip().lower() == "rejected":
        return "rejected"
    if state.get("errors"):
        return "error"
    return "completed"


# ---------------------------------------------------------------------------
# Routes
# ---------------------------------------------------------------------------

@app.get("/health")
def health() -> dict:
    return {"status": "ok", "provider": settings.ai_provider, "model": settings.ai_model}


@app.get("/metrics")
def get_metrics() -> dict[str, Any]:
    """
    Expose rate-limiter counters.

    Returns Prometheus-ready counter names so a future /metrics scrape endpoint
    can map them directly to prometheus_client.Counter objects.

    Example response:
        {
            "requests_total": 42,
            "requests_delayed": 7,
            "requests_retried": 2,
            "tokens_consumed": 18400,
            "rate_limit_hits": 7
        }
    """
    instance = GeminiRateLimiter._instance
    if instance is None:
        return {}
    return instance.metrics


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
    log.info(
        "workflow_run_started",
        extra={
            "event": "workflow_run_started",
            "thread_id": thread_id,
            "user_id": req.user_id,
            "target_role": req.target_role,
        },
    )
    try:
        log.info(
            "workflow_invoke_begin",
            extra={
                "event": "workflow_invoke_begin",
                "thread_id": thread_id,
            },
        )
        final_state = graph.invoke(initial, config=_config(thread_id))
        log.info(
            "workflow_invoke_complete",
            extra={
                "event": "workflow_invoke_complete",
                "thread_id": thread_id,
                "state_keys": list(final_state.keys()),
                "has_errors": "errors" in final_state and bool(final_state.get("errors")),
            },
        )
    except Exception as e:  # noqa: BLE001
        log.error(
            "workflow_invoke_exception",
            extra={
                "event": "workflow_invoke_exception",
                "thread_id": thread_id,
                "exception_type": type(e).__name__,
                "exception_msg": str(e),
            },
            exc_info=True,
        )
        snapshot = graph.get_state(_config(thread_id))
        state = dict(snapshot.values) if snapshot else {}
        next_nodes = tuple(snapshot.next) if snapshot and snapshot.next else ()
        # A *genuine* human-approval pause parks the graph exactly at human_approval
        # with the await flag set and no recorded errors. ANYTHING else reaching this
        # handler is a real agent failure. The previous `if snapshot.next` heuristic
        # was wrong: a failed node is *also* left in `snapshot.next` (pending retry),
        # so a crash at resume_intelligence was reported as "interrupted" — offering
        # the user Approve/Reject on a dead run, which then produced the impossible
        # timeline state. Surface real failures as status="error" (never a 500,
        # never a fake interrupt) so the run stays inspectable.
        if (
            "human_approval" in next_nodes
            and state.get("awaiting_human_approval")
            and not state.get("errors")
        ):
            log.info(
                "workflow_interrupted_return",
                extra={
                    "event": "workflow_interrupted_return",
                    "thread_id": thread_id,
                    "state_keys": list(state.keys()),
                },
            )
            return RunResponse(thread_id=thread_id, status="interrupted", state=state)
        errors = list(state.get("errors") or [])
        errors.append(f"Run failed: {type(e).__name__}: {e}")
        state["errors"] = errors
        state["awaiting_human_approval"] = False
        log.exception("workflow_run_failed", extra={"event": "workflow_run_failed", "thread_id": thread_id})
        return RunResponse(thread_id=thread_id, status="error", state=state)

    status = _classify(final_state)
    log.info(
        "workflow_status_classified",
        extra={
            "event": "workflow_status_classified",
            "thread_id": thread_id,
            "status": status,
        },
    )

    response = RunResponse(thread_id=thread_id, status=status, state=final_state)
    log.info(
        "response_created",
        extra={
            "event": "response_created",
            "thread_id": thread_id,
            "response_status": response.status,
            "response_state_keys": list(response.state.keys()),
        },
    )
    return response


@app.post("/runs/resume", response_model=RunResponse)
def resume_run(req: ResumeRunRequest) -> RunResponse:
    graph = get_compiled_graph()
    cfg = _config(req.thread_id)
    snapshot = graph.get_state(cfg)
    if not snapshot:
        raise HTTPException(status_code=404, detail="thread not found")

    # Idempotency / invalid-state guard (Scenario E/F): a run may be resumed ONLY
    # while it is genuinely parked at the human_approval gate. LangGraph leaves
    # "human_approval" in snapshot.next exactly while the NodeInterrupt is pending;
    # once a decision has been applied (approved/rejected) — or the run failed before
    # ever reaching the gate — it is no longer there. Without this guard, a double-click
    # or a late/duplicate decision re-runs update_state()+invoke(), which can flip an
    # already-REJECTED run to "completed" via _classify() (the decision is overwritten
    # to "approved" and no error remains). Returning 409 makes the second decision a
    # no-op and keeps the terminal status immutable.
    next_nodes = tuple(snapshot.next) if snapshot.next else ()
    if "human_approval" not in next_nodes:
        log.info(
            "workflow_resume_rejected_not_awaiting",
            extra={
                "event": "workflow_resume_rejected_not_awaiting",
                "thread_id": req.thread_id,
                "next_nodes": list(next_nodes),
                "decision": req.human_decision,
            },
        )
        raise HTTPException(status_code=409, detail="Run is not awaiting approval")

    graph.update_state(
        cfg,
        {
            "human_decision": req.human_decision,
            "human_feedback": req.human_feedback,
            "awaiting_human_approval": False,
        },
    )
    log.info(
        "workflow_run_resumed",
        extra={
            "event": "workflow_run_resumed",
            "thread_id": req.thread_id,
            "decision": req.human_decision,
        },
    )
    try:
        final_state = graph.invoke(None, config=cfg)
    except Exception as e:  # noqa: BLE001
        # An agent failing during resume (e.g. application_tracking hitting an AI
        # provider error) must NOT surface as a 500 to the backend/UI. Recover the
        # last checkpointed state, record the error, and return status="error" so
        # the workflow stays inspectable instead of dead. (Mirrors /runs.)
        log.error(
            "workflow_resume_exception",
            extra={
                "event": "workflow_resume_exception",
                "thread_id": req.thread_id,
                "decision": req.human_decision,
                "exception_type": type(e).__name__,
                "exception_msg": str(e),
            },
            exc_info=True,
        )
        snapshot = graph.get_state(cfg)
        state = dict(snapshot.values) if snapshot else {}
        errors = list(state.get("errors") or [])
        errors.append(f"Resume failed: {type(e).__name__}: {e}")
        state["errors"] = errors
        state["awaiting_human_approval"] = False
        return RunResponse(thread_id=req.thread_id, status="error", state=state)

    return RunResponse(thread_id=req.thread_id, status=_classify(final_state), state=final_state)


@app.get("/runs/{thread_id}", response_model=RunResponse)
def get_run(thread_id: str) -> RunResponse:
    graph = get_compiled_graph()
    snapshot = graph.get_state(_config(thread_id))
    if not snapshot:
        raise HTTPException(status_code=404, detail="thread not found")
    status = "interrupted" if snapshot.next else _classify(snapshot.values)
    return RunResponse(thread_id=thread_id, status=status, state=snapshot.values)
