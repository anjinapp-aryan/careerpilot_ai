"""LangGraph workflow: 8-agent CareerPilot pipeline with Postgres checkpointing + HITL."""
from __future__ import annotations

import logging
from functools import lru_cache

from langgraph.checkpoint.postgres import PostgresSaver
from langgraph.graph import END, START, StateGraph
from psycopg_pool import ConnectionPool

from .agents.application_tracking import application_tracking_node
from .agents.ats_optimization import ats_optimization_node
from .agents.career_strategy import career_strategy_node
from .agents.human_approval import human_approval_node
from .agents.interview_prep import interview_prep_node
from .agents.job_discovery import job_discovery_node
from .agents.resume_intelligence import resume_intelligence_node
from .agents.salary_intelligence import salary_intelligence_node
from .config import settings
from .state import CareerState

log = logging.getLogger(__name__)


@lru_cache(maxsize=1)
def _pool() -> ConnectionPool:
    pool = ConnectionPool(
        conninfo=settings.database_url,
        max_size=10,
        kwargs={"autocommit": True, "prepare_threshold": 0},
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
    g.add_node("resume_intelligence", resume_intelligence_node)
    g.add_node("job_discovery", job_discovery_node)
    g.add_node("ats_optimization", ats_optimization_node)
    g.add_node("interview_prep", interview_prep_node)
    g.add_node("career_strategy", career_strategy_node)
    g.add_node("salary_intelligence", salary_intelligence_node)
    g.add_node("human_approval", human_approval_node)
    g.add_node("application_tracking", application_tracking_node)

    g.add_edge(START, "resume_intelligence")
    g.add_edge("resume_intelligence", "job_discovery")
    g.add_edge("job_discovery", "ats_optimization")
    g.add_edge("ats_optimization", "interview_prep")
    g.add_edge("interview_prep", "career_strategy")
    g.add_edge("career_strategy", "salary_intelligence")
    g.add_edge("salary_intelligence", "human_approval")
    g.add_edge("human_approval", "application_tracking")
    g.add_edge("application_tracking", END)
    return g


@lru_cache(maxsize=1)
def get_compiled_graph():
    return _build_graph().compile(checkpointer=_checkpointer())
