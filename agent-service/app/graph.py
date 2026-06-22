"""LangGraph workflow: 8-agent CareerPilot pipeline with Postgres checkpointing + HITL."""
from __future__ import annotations

import logging
import time
from datetime import datetime, timezone
from functools import lru_cache
from typing import Callable

from langgraph.checkpoint.postgres import PostgresSaver
from langgraph.graph import END, START, StateGraph
from psycopg_pool import ConnectionPool

from .agents.application_tracking import application_tracking_node
from .agents.ats_optimization import ats_optimization_node
from .agents.career_strategy import career_strategy_node
from .agents.human_approval import human_approval_node
from .agents.interview_prep import interview_prep_node
from .agents.job_discovery import job_discovery_node
from .agents.resume_export import resume_export_node
from .agents.resume_intelligence import resume_intelligence_node
from .agents.salary_intelligence import salary_intelligence_node
from .config import settings
from .state import CareerState
from .workflow_ai_gateway import get_stage_provider

log = logging.getLogger(__name__)


def _workflow_type(state: CareerState) -> str:
    """Normalize the workflow template selector. Absent => the original pipeline."""
    return (state.get("workflow_type") or "full_career").strip().lower()


def _route_after_resume(state: CareerState) -> str:
    """
    Branch after Resume Intelligence by workflow template.

    full_career         -> job_discovery (the original 8-node pipeline)
    resume_optimization -> ats_optimization (skip job/interview/career/salary)
    """
    routed = "resume_optimization" if _workflow_type(state) == "resume_optimization" else "full_career"
    log.info("resume_routed", extra={"event": "resume_routed", "route": routed})
    return routed


def _route_after_ats(state: CareerState) -> str:
    """
    Branch after ATS Optimization by workflow template.

    full_career         -> interview_prep (continue the original pipeline)
    resume_optimization -> human_approval (go straight to the approval gate)
    """
    routed = "resume_optimization" if _workflow_type(state) == "resume_optimization" else "full_career"
    log.info("ats_routed", extra={"event": "ats_routed", "route": routed})
    return routed


def _route_after_approval(state: CareerState) -> str:
    """
    Branch the graph on the human decision AND the workflow template.

    A rejection must STOP the pipeline (it may not run any post-approval node —
    that would make Reject behave like Approve). An approval routes to the
    template's terminal node: application_tracking for full_career, resume_export
    for resume_optimization.
    """
    decision = (state.get("human_decision") or "").strip().lower()
    if decision == "rejected":
        routed = "rejected"
    elif _workflow_type(state) == "resume_optimization":
        routed = "approved_resume"
    else:
        routed = "approved_full"
    log.info(
        "approval_routed",
        extra={"event": "approval_routed", "decision": decision, "route": routed},
    )
    return routed


def _utc_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _instrument(stage: str, display_name: str, fn: Callable[[CareerState], dict]) -> Callable[[CareerState], dict]:
    """
    Wrap an agent node so each run appends one entry to `agent_execution`:
    {stage, name, status, started_at, completed_at, duration_ms, provider, error}.

    Provides the data behind the execution timeline (Issue 3), provider
    attribution (Issue 8), and structured stage observability (Issue 9).

    NB: `human_approval` is intentionally NOT wrapped — it raises NodeInterrupt
    to pause the graph; wrapping it would swallow the interrupt or emit a
    duplicate/failed entry on resume.
    """

    def wrapped(state: CareerState) -> dict:
        started_at = _utc_iso()
        t0 = time.monotonic()
        log.info(
            "stage_start",
            # NB: "name" is a RESERVED LogRecord attribute — putting it in `extra`
            # makes stdlib logging raise KeyError("Attempt to overwrite 'name'...").
            # Use "display_name" so the instrumented log never crashes the node.
            extra={"event": "stage_start", "stage": stage, "display_name": display_name, "started_at": started_at},
        )
        try:
            result = fn(state) or {}
        except Exception as e:
            duration_ms = int((time.monotonic() - t0) * 1000)
            log.error(
                "stage_failed",
                extra={
                    "event": "stage_failed",
                    "stage": stage,
                    "display_name": display_name,
                    "duration_ms": duration_ms,
                    "error_type": type(e).__name__,
                    "error": str(e),
                },
                exc_info=True,
            )
            # On a raised exception LangGraph won't apply a partial state update,
            # so we can't append a telemetry entry here. Agents already guard
            # their own bodies and return {"errors": [...]} instead of raising,
            # which the COMPLETED-with-errors path below records as FAILED.
            raise

        duration_ms = int((time.monotonic() - t0) * 1000)
        errors = result.get("errors") or []
        status = "FAILED" if errors else "COMPLETED"
        entry = {
            "stage": stage,
            "name": display_name,
            "status": status,
            "started_at": started_at,
            "completed_at": _utc_iso(),
            "duration_ms": duration_ms,
            "provider": get_stage_provider(stage),
            "error": errors[0] if errors else None,
        }
        log.info(
            "stage_end",
            extra={
                "event": "stage_end",
                "stage": stage,
                "display_name": display_name,
                "status": status,
                "duration_ms": duration_ms,
                "provider": entry["provider"],
            },
        )
        # operator.add merge appends this single entry to agent_execution.
        return {**result, "agent_execution": [entry]}

    return wrapped


@lru_cache(maxsize=1)
def _pool() -> ConnectionPool:
    # Neon serverless closes idle server-side connections well under common
    # client pool-idle defaults. Without `check`, a connection that Neon already
    # dropped looks healthy at checkout time and the first query against it
    # fails mid-request with psycopg.OperationalError ("SSL SYSCALL error: EOF
    # detected") inside graph.invoke()/get_state(). `check_connection` runs a
    # cheap liveness probe on checkout and transparently replaces a dead
    # connection instead of handing it to the caller; `max_idle` proactively
    # recycles connections before Neon's own idle timeout can fire; the libpq
    # keepalive kwargs detect a half-open socket faster than TCP defaults.
    pool = ConnectionPool(
        conninfo=settings.database_url,
        max_size=10,
        kwargs={
            "autocommit": True,
            "prepare_threshold": 0,
            "keepalives": 1,
            "keepalives_idle": 30,
            "keepalives_interval": 10,
            "keepalives_count": 3,
        },
        check=ConnectionPool.check_connection,
        max_idle=180,
        open=True,
    )
    return pool


@lru_cache(maxsize=1)
def _checkpointer() -> PostgresSaver:
    saver = PostgresSaver(_pool())
    saver.setup()  # idempotent; creates checkpoint tables on first run
    return saver


def _build_graph() -> StateGraph:
    g = StateGraph(CareerState)
    # Every agent node EXCEPT human_approval is wrapped to record execution
    # telemetry (timing/status/provider) into `agent_execution`. human_approval
    # raises NodeInterrupt and must stay un-instrumented (see _instrument docs).
    g.add_node("resume_intelligence", _instrument("resume_intelligence", "Resume Intelligence", resume_intelligence_node))
    g.add_node("job_discovery", _instrument("job_discovery", "Job Discovery", job_discovery_node))
    g.add_node("ats_optimization", _instrument("ats_optimization", "ATS Optimization", ats_optimization_node))
    g.add_node("interview_prep", _instrument("interview_prep", "Interview Preparation", interview_prep_node))
    g.add_node("career_strategy", _instrument("career_strategy", "Career Strategy", career_strategy_node))
    g.add_node("salary_intelligence", _instrument("salary_intelligence", "Salary Intelligence", salary_intelligence_node))
    g.add_node("human_approval", human_approval_node)
    g.add_node("application_tracking", _instrument("application_tracking", "Application Tracking", application_tracking_node))
    g.add_node("resume_export", _instrument("resume_export", "Resume Export", resume_export_node))

    g.add_edge(START, "resume_intelligence")
    # Template fork after Resume Intelligence. full_career runs the original
    # pipeline; resume_optimization skips straight to ATS. Both share the
    # resume_intelligence + ats_optimization nodes unchanged. An absent
    # workflow_type resolves to full_career, so existing runs are unchanged.
    g.add_conditional_edges(
        "resume_intelligence",
        _route_after_resume,
        {"full_career": "job_discovery", "resume_optimization": "ats_optimization"},
    )
    g.add_edge("job_discovery", "ats_optimization")
    # Template fork after ATS. full_career continues; resume_optimization goes
    # directly to the human approval gate.
    g.add_conditional_edges(
        "ats_optimization",
        _route_after_ats,
        {"full_career": "interview_prep", "resume_optimization": "human_approval"},
    )
    g.add_edge("interview_prep", "career_strategy")
    g.add_edge("career_strategy", "salary_intelligence")
    g.add_edge("salary_intelligence", "human_approval")
    # Conditional: approval routes to the template's terminal node
    # (application_tracking for full_career, resume_export for
    # resume_optimization); rejection ends the graph so a rejected run never
    # executes a post-approval node. Without this branch, Reject and Approve
    # produce identical execution (the original bug).
    g.add_conditional_edges(
        "human_approval",
        _route_after_approval,
        {
            "approved_full": "application_tracking",
            "approved_resume": "resume_export",
            "rejected": END,
        },
    )
    g.add_edge("application_tracking", END)
    g.add_edge("resume_export", END)
    return g


@lru_cache(maxsize=1)
def get_compiled_graph():
    return _build_graph().compile(checkpointer=_checkpointer())
