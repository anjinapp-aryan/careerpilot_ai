"""LangGraph workflow: Skill Gap Intelligence — 6-agent linear pipeline, no checkpointing.

Deliberately does not touch `app/graph.py`'s `_build_graph()`/`get_compiled_graph()` — this is a
second, independent `StateGraph` over a different state type. Stateless by design: unlike the main
career graph (which pauses at a human-approval `NodeInterrupt` and needs `PostgresSaver` to survive
that pause across requests), this workflow computes and returns within a single request/response
cycle, so no checkpoint table, no Postgres connection pool, and no extra resource footprint on the
2GB-RAM Oracle Cloud Free Tier VM.
"""
from __future__ import annotations

from functools import lru_cache

from langgraph.graph import END, START, StateGraph

from .agents.learning_roadmap import learning_roadmap_node
from .agents.market_intelligence import market_intelligence_node
from .agents.mission_context import mission_context_node
from .agents.mission_readiness import mission_readiness_node
from .agents.resume_intelligence import resume_intelligence_node
from .agents.skill_gap import skill_gap_node
from .state import SkillGapState


def _build_skill_gap_graph() -> StateGraph:
    g = StateGraph(SkillGapState)
    # NB: the "mission_context" node is named "mission_context_agent" — LangGraph
    # forbids a node id that collides with a state key (SkillGapState.mission_context
    # is the field this node writes). Same collision-avoidance already documented
    # in app/state.py for CareerState.salary_insights vs. the salary_intelligence node.
    g.add_node("mission_context_agent", mission_context_node)
    g.add_node("resume_intelligence", resume_intelligence_node)
    g.add_node("market_intelligence", market_intelligence_node)
    g.add_node("skill_gap", skill_gap_node)
    g.add_node("learning_roadmap", learning_roadmap_node)
    g.add_node("mission_readiness", mission_readiness_node)

    g.add_edge(START, "mission_context_agent")
    g.add_edge("mission_context_agent", "resume_intelligence")
    g.add_edge("resume_intelligence", "market_intelligence")
    g.add_edge("market_intelligence", "skill_gap")
    g.add_edge("skill_gap", "learning_roadmap")
    g.add_edge("learning_roadmap", "mission_readiness")
    g.add_edge("mission_readiness", END)
    return g


@lru_cache(maxsize=1)
def get_compiled_skill_gap_graph():
    return _build_skill_gap_graph().compile()
