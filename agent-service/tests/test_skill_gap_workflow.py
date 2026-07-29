"""
Tests for the Skill Gap Intelligence Workflow (app.skillgap.*).

Covers:
  1. Deterministic nodes (no AI call)     -> mission_context, skill_gap, the
                                              readiness_score/confidence formulas
  2. Graph structure                       -> test_graph_compiles_all_six_nodes
  3. End-to-end via the FastAPI router     -> test_skill_gap_run_end_to_end_matches_worked_example
                                               (reproduces the Phase 10 spec's own
                                               "Senior Java Architect / Germany" example)
  4. Error handling                        -> a raising AI Gateway never surfaces as a 500;
                                               it degrades to a structured status="error" response

Run with:
    pip install -r requirements-dev.txt
    pytest tests/test_skill_gap_workflow.py -v
"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.skillgap.agents.mission_context import mission_context_node
from app.skillgap.agents.mission_readiness import _confidence, _readiness_score
from app.skillgap.agents.skill_gap import skill_gap_node
from app.skillgap.graph import get_compiled_skill_gap_graph


# ---------------------------------------------------------------------------
# Deterministic nodes — no AI Gateway involved
# ---------------------------------------------------------------------------

def test_mission_context_builds_a_summary_from_structured_fields():
    result = mission_context_node({
        "target_role": "Senior Java Architect",
        "target_level": "Senior",
        "target_countries": ["Germany"],
        "timeline_months": 12,
    })

    ctx = result["mission_context"]
    assert ctx["target_role"] == "Senior Java Architect"
    assert ctx["target_countries"] == ["Germany"]
    assert ctx["timeline_months"] == 12
    assert "Germany" in ctx["mission_summary"]
    assert "12 months" in ctx["mission_summary"]


def test_mission_context_prefers_the_explicit_mission_statement_when_present():
    result = mission_context_node({
        "mission_statement": "Become Principal Engineer",
        "target_role": "Senior Java Architect",
        "target_countries": ["Germany"],
    })

    assert result["mission_context"]["mission_summary"].startswith("Become Principal Engineer")


def test_skill_gap_node_filters_known_skills_and_buckets_by_priority():
    state = {
        "candidate_profile": {"skills": ["Java", "Spring Boot"], "technologies": ["AWS", "Docker"]},
        "market_expectations": {
            "expected_skills": [
                {"skill": "Java", "priority": "CRITICAL", "reason": "already known"},
                {"skill": "Kubernetes", "priority": "CRITICAL", "reason": "market standard"},
                {"skill": "System Design", "priority": "IMPORTANT", "reason": "senior expectation"},
                {"skill": "Rust", "priority": "OPTIONAL", "reason": "nice to have"},
            ]
        },
    }

    result = skill_gap_node(state)

    assert [g["skill"] for g in result["critical_skill_gaps"]] == ["Kubernetes"]
    assert [g["skill"] for g in result["important_skill_gaps"]] == ["System Design"]
    assert [g["skill"] for g in result["optional_skill_gaps"]] == ["Rust"]


def test_skill_gap_node_is_case_insensitive_against_known_skills():
    state = {
        "candidate_profile": {"skills": ["kubernetes"], "technologies": []},
        "market_expectations": {"expected_skills": [{"skill": "Kubernetes", "priority": "CRITICAL", "reason": "x"}]},
    }

    result = skill_gap_node(state)

    assert result["critical_skill_gaps"] == []


def test_readiness_score_and_confidence_formulas():
    # 1 critical + 1 important gap -> 100 - 15 - 7 = 78, matching the Phase 10
    # spec's own worked example readinessScore.
    assert _readiness_score(critical=1, important=1, optional=0) == 78
    assert _readiness_score(critical=0, important=0, optional=0) == 100
    assert _readiness_score(critical=10, important=0, optional=0) == 0  # clamped at 0

    assert _confidence(critical=0, important=0, has_errors=False) == 0.95
    assert _confidence(critical=1, important=1, has_errors=False) < 0.95
    assert _confidence(critical=0, important=0, has_errors=True) == 0.6


# ---------------------------------------------------------------------------
# Graph structure
# ---------------------------------------------------------------------------

def test_graph_compiles_all_six_nodes():
    graph = get_compiled_skill_gap_graph()
    node_names = set(graph.get_graph().nodes.keys())
    for expected in (
        "mission_context_agent", "resume_intelligence", "market_intelligence",
        "skill_gap", "learning_roadmap", "mission_readiness",
    ):
        assert expected in node_names


# ---------------------------------------------------------------------------
# End-to-end via the FastAPI router, AI Gateway calls faked
# ---------------------------------------------------------------------------

class _FakeGateway:
    """Stands in for WorkflowAiGateway — returns a canned, stage-keyed response."""

    def __init__(self, by_stage: dict[str, dict], raise_on: set[str] | None = None):
        self._by_stage = by_stage
        self._raise_on = raise_on or set()

    def generate_structured_response(self, prompt, schema, *, system=None, stage="unknown"):
        if stage in self._raise_on:
            raise RuntimeError(f"all providers failed for stage '{stage}'")
        return self._by_stage.get(stage, {})


_MARKET_RESPONSE = {
    "expected_skills": [
        {"skill": "Java", "priority": "CRITICAL", "reason": "already known, filtered out"},
        {"skill": "Kubernetes", "priority": "CRITICAL", "reason": "container orchestration is standard"},
        {"skill": "System Design", "priority": "IMPORTANT", "reason": "expected at senior level"},
    ],
    "country_notes": "Germany strongly prefers demonstrable system design depth for architect roles.",
    "industry_trends": ["cloud-native architecture", "platform engineering"],
}
_ROADMAP_RESPONSE = {
    "roadmap": [
        {
            "skill": "Kubernetes", "priority": "CRITICAL", "estimated_duration_weeks": 6,
            "difficulty": "MEDIUM", "depends_on": [], "business_impact": "HIGH",
        },
        {
            "skill": "System Design", "priority": "IMPORTANT", "estimated_duration_weeks": 8,
            "difficulty": "HIGH", "depends_on": [], "business_impact": "HIGH",
        },
    ],
    "estimated_completion_months": 6,
}
_READINESS_RESPONSE = {
    "strengths": ["8 years of hands-on Java/Spring Boot/Microservices experience"],
    "risks": ["No demonstrated Kubernetes experience for a market that expects it"],
    "recommendations": ["Complete a Kubernetes certification before applying"],
}


@pytest.fixture
def _fake_ai_gateway(monkeypatch: pytest.MonkeyPatch):
    """Patches the single choke point every AI-calling skill-gap agent now shares:
    app.agent_support.call_structured_agent's own get_workflow_ai_gateway import (Phase 10A)."""
    fake = _FakeGateway({
        "skill_gap_market_intelligence": _MARKET_RESPONSE,
        "skill_gap_learning_roadmap": _ROADMAP_RESPONSE,
        "skill_gap_mission_readiness": _READINESS_RESPONSE,
    })
    import app.agent_support as agent_support_module

    monkeypatch.setattr(agent_support_module, "get_workflow_ai_gateway", lambda: fake)
    yield fake


def test_skill_gap_run_end_to_end_matches_worked_example(_fake_ai_gateway):
    client = TestClient(app)

    response = client.post("/skill-gap/runs", json={
        "mission_id": "11111111-1111-1111-1111-111111111111",
        "user_id": "22222222-2222-2222-2222-222222222222",
        "workflow_id": "SKILL_GAP_INTELLIGENCE_V1",
        "execution_id": "exec-1",
        "correlation_id": "corr-1",
        "target_role": "Senior Java Architect",
        "target_level": "Senior",
        "target_countries": ["Germany"],
        "timeline_months": 12,
        "current_skills": ["Java", "Spring Boot", "Microservices", "AWS", "Docker", "Kafka"],
        "experience_years": 8,
    })

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "completed"
    assert body["missionId"] == "11111111-1111-1111-1111-111111111111"
    assert body["executionId"] == "exec-1"
    # Deterministic: 1 critical (Kubernetes) + 1 important (System Design) -> 78
    assert body["readinessScore"] == 78
    assert body["missionProgress"] == 0.78
    assert [g["skill"] for g in body["criticalSkillGaps"]] == ["Kubernetes"]
    assert [g["skill"] for g in body["importantSkillGaps"]] == ["System Design"]
    assert body["optionalSkillGaps"] == []
    assert len(body["recommendedLearningRoadmap"]) == 2
    assert body["estimatedCompletionMonths"] == 6
    assert body["strengths"]
    assert body["risks"]
    assert body["recommendations"]
    assert body["errors"] == []


def test_skill_gap_run_degrades_to_structured_error_never_a_500(monkeypatch: pytest.MonkeyPatch):
    import app.agent_support as agent_support_module

    def _raise():
        raise RuntimeError("all providers failed for stage 'skill_gap_market_intelligence'")

    monkeypatch.setattr(agent_support_module, "get_workflow_ai_gateway", _raise)

    client = TestClient(app)
    response = client.post("/skill-gap/runs", json={
        "mission_id": "m1", "user_id": "u1", "target_role": "Senior Java Architect",
        "target_countries": ["Germany"], "current_skills": ["Java"],
    })

    assert response.status_code == 200  # never a 500 — structured error instead
    body = response.json()
    assert body["status"] == "error"
    assert body["errors"]
