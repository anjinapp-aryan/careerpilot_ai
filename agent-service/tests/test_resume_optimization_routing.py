"""
Tests for the RESUME_OPTIMIZATION workflow template routing.

The graph is a single StateGraph with conditional edges keyed on `workflow_type`:
  - absent / "full_career"  -> the original 8-node linear pipeline
  - "resume_optimization"   -> resume_intelligence -> ats_optimization
                               -> human_approval -> resume_export

These tests cover the pure routing functions (no DB / checkpointer needed) plus a
structural compile of the graph to prove every conditional-edge target is valid.

Run with:
    pip install -r requirements-dev.txt
    pytest tests/test_resume_optimization_routing.py -v
"""
from __future__ import annotations

from app.graph import (
    _build_graph,
    _route_after_approval,
    _route_after_ats,
    _route_after_resume,
)


# --- after resume_intelligence -------------------------------------------------

def test_route_after_resume_defaults_to_full_career():
    assert _route_after_resume({}) == "full_career"
    assert _route_after_resume({"workflow_type": "full_career"}) == "full_career"


def test_route_after_resume_resume_optimization():
    assert _route_after_resume({"workflow_type": "resume_optimization"}) == "resume_optimization"
    # case-insensitive / padded
    assert _route_after_resume({"workflow_type": " RESUME_OPTIMIZATION "}) == "resume_optimization"


# --- after ats_optimization ----------------------------------------------------

def test_route_after_ats_defaults_to_full_career():
    assert _route_after_ats({}) == "full_career"


def test_route_after_ats_resume_optimization_goes_to_approval():
    assert _route_after_ats({"workflow_type": "resume_optimization"}) == "resume_optimization"


# --- after human_approval ------------------------------------------------------

def test_route_after_approval_rejected_always_ends():
    assert _route_after_approval({"human_decision": "rejected"}) == "rejected"
    assert _route_after_approval(
        {"human_decision": "rejected", "workflow_type": "resume_optimization"}
    ) == "rejected"


def test_route_after_approval_full_career_goes_to_tracking():
    assert _route_after_approval({"human_decision": "approved"}) == "approved_full"


def test_route_after_approval_resume_optimization_goes_to_export():
    assert _route_after_approval(
        {"human_decision": "approved", "workflow_type": "resume_optimization"}
    ) == "approved_resume"


# --- structural integrity ------------------------------------------------------

def test_graph_compiles_without_checkpointer():
    """Compiling (without a checkpointer, so no DB) validates that every node and
    conditional-edge target — including the new resume_export node — exists."""
    compiled = _build_graph().compile()
    assert compiled is not None


def test_resume_export_node_registered():
    graph = _build_graph()
    assert "resume_export" in graph.nodes
