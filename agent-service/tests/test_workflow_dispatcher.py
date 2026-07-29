"""
Tests for the generic Workflow Dispatcher (app.dispatcher.*), Phase 10A.

Covers:
  1. Registry semantics (register/get/list/unknown)
  2. Generic HTTP dispatch, using a trivial synthetic workflow (no AI Gateway involved)
  3. The Skill Gap registration reused end-to-end through the generic dispatcher, proving it
     produces the same underlying result as the dedicated `/skill-gap/runs` endpoint
  4. Backward compatibility — `/runs` and `/skill-gap/runs` are untouched by this phase

Run with:
    pip install -r requirements-dev.txt
    pytest tests/test_workflow_dispatcher.py -v
"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.dispatcher.registry import (
    WorkflowDispatchRegistry,
    WorkflowNotRegisteredError,
    WorkflowRegistration,
    get_dispatch_registry,
)
from app.main import app


# ---------------------------------------------------------------------------
# Registry semantics
# ---------------------------------------------------------------------------

def test_registry_returns_the_registered_workflow():
    registry = WorkflowDispatchRegistry()
    registration = WorkflowRegistration(
        workflow_id="ECHO_V1", graph_factory=lambda: None,
        state_mapper=lambda req, eid, cid: {}, output_mapper=lambda s: {},
    )

    registry.register(registration)

    assert registry.get("ECHO_V1") is registration
    assert registry.list_ids() == ["ECHO_V1"]


def test_registry_raises_for_unknown_workflow():
    registry = WorkflowDispatchRegistry()

    with pytest.raises(WorkflowNotRegisteredError):
        registry.get("DOES_NOT_EXIST")


def test_global_registry_already_has_skill_gap_registered():
    # main.py registers this at import time; proves the module-level bootstrap ran.
    assert "SKILL_GAP_INTELLIGENCE_V1" in get_dispatch_registry().list_ids()


# ---------------------------------------------------------------------------
# Generic HTTP dispatch — a trivial synthetic workflow, no AI Gateway
# ---------------------------------------------------------------------------

class _EchoGraph:
    """A minimal stand-in graph: echoes its input back as output, no LangGraph involved."""

    def invoke(self, initial_state: dict) -> dict:
        return {**initial_state, "echoed": True}


@pytest.fixture
def _echo_workflow():
    registry = get_dispatch_registry()
    registry.register(WorkflowRegistration(
        workflow_id="ECHO_TEST_V1",
        graph_factory=_EchoGraph,
        state_mapper=lambda req, eid, cid: {"execution_id": eid, "correlation_id": cid, "errors": [], **req.inputs},
        output_mapper=lambda state: {"echoed": state.get("echoed"), "value": state.get("value")},
    ))
    yield
    # no explicit teardown API on the registry; a stale ECHO_TEST_V1 entry across test runs is
    # harmless (same id always re-registers to the same trivial fixture)


def test_dispatch_run_returns_404_for_an_unregistered_workflow_id():
    client = TestClient(app)

    response = client.post("/workflows/NOT_REGISTERED/runs", json={"mission_id": "m1", "user_id": "u1"})

    assert response.status_code == 404


def test_dispatch_run_succeeds_for_a_registered_workflow(_echo_workflow):
    client = TestClient(app)

    response = client.post("/workflows/ECHO_TEST_V1/runs", json={
        "mission_id": "m1", "user_id": "u1", "execution_id": "exec-1", "correlation_id": "corr-1",
        "inputs": {"value": "hello"},
    })

    assert response.status_code == 200
    body = response.json()
    assert body["workflowId"] == "ECHO_TEST_V1"
    assert body["executionId"] == "exec-1"
    assert body["correlationId"] == "corr-1"
    assert body["status"] == "completed"
    assert body["output"] == {"echoed": True, "value": "hello"}
    assert body["errors"] == []
    assert body["durationMs"] >= 0


def test_dispatch_run_generates_ids_when_the_caller_omits_them(_echo_workflow):
    client = TestClient(app)

    response = client.post("/workflows/ECHO_TEST_V1/runs", json={"mission_id": "m1", "user_id": "u1"})

    body = response.json()
    assert body["executionId"]
    assert body["correlationId"]


def test_dispatch_run_degrades_to_structured_error_never_a_500():
    registry = get_dispatch_registry()

    class _RaisingGraph:
        def invoke(self, _initial_state: dict) -> dict:
            raise RuntimeError("boom")

    registry.register(WorkflowRegistration(
        workflow_id="RAISING_TEST_V1", graph_factory=_RaisingGraph,
        state_mapper=lambda req, eid, cid: {}, output_mapper=lambda s: {},
    ))

    client = TestClient(app)
    response = client.post("/workflows/RAISING_TEST_V1/runs", json={"mission_id": "m1", "user_id": "u1"})

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "error"
    assert body["errors"]


def test_list_registered_workflows_includes_skill_gap():
    client = TestClient(app)

    response = client.get("/workflows")

    assert response.status_code == 200
    assert "SKILL_GAP_INTELLIGENCE_V1" in response.json()["workflowIds"]


# ---------------------------------------------------------------------------
# Skill Gap reused end-to-end through the generic dispatcher — the reference proof
# ---------------------------------------------------------------------------

def test_skill_gap_runs_end_to_end_through_the_generic_dispatcher(monkeypatch: pytest.MonkeyPatch):
    """Same worked example as test_skill_gap_workflow.py's dedicated-endpoint test, but invoked
    through the generic dispatcher instead — proving Skill Gap needed zero code changes to become
    dispatchable, and that the generic envelope carries the same underlying result."""
    import app.agent_support as agent_support_module

    class _FakeGateway:
        def __init__(self, by_stage: dict[str, dict]):
            self._by_stage = by_stage

        def generate_structured_response(self, prompt, schema, *, system=None, stage="unknown"):
            return self._by_stage.get(stage, {})

    fake = _FakeGateway({
        "skill_gap_market_intelligence": {
            "expected_skills": [
                {"skill": "Java", "priority": "CRITICAL", "reason": "already known"},
                {"skill": "Kubernetes", "priority": "CRITICAL", "reason": "market standard"},
            ],
            "country_notes": "", "industry_trends": [],
        },
        "skill_gap_learning_roadmap": {
            "roadmap": [{
                "skill": "Kubernetes", "priority": "CRITICAL", "estimated_duration_weeks": 6,
                "difficulty": "MEDIUM", "depends_on": [], "business_impact": "HIGH",
            }],
            "estimated_completion_months": 6,
        },
        "skill_gap_mission_readiness": {"strengths": ["Java"], "risks": ["No k8s"], "recommendations": ["Learn k8s"]},
    })
    monkeypatch.setattr(agent_support_module, "get_workflow_ai_gateway", lambda: fake)

    client = TestClient(app)
    response = client.post("/workflows/SKILL_GAP_INTELLIGENCE_V1/runs", json={
        "mission_id": "m1", "user_id": "u1", "execution_id": "exec-generic-1", "correlation_id": "corr-1",
        "inputs": {
            "target_role": "Senior Java Architect", "target_countries": ["Germany"],
            "current_skills": ["Java", "Spring Boot"], "timeline_months": 12,
        },
    })

    assert response.status_code == 200
    body = response.json()
    assert body["workflowId"] == "SKILL_GAP_INTELLIGENCE_V1"
    assert body["executionId"] == "exec-generic-1"
    assert body["status"] == "completed"
    assert body["output"]["readiness_score"] == 85  # 1 critical gap only (Java is already known) -> 100 - 15
    assert body["output"]["critical_skill_gaps"] == [{"skill": "Kubernetes", "priority": "CRITICAL", "reason": "market standard"}]


# ---------------------------------------------------------------------------
# Backward compatibility — existing endpoints untouched
# ---------------------------------------------------------------------------

def test_existing_runs_endpoint_still_exists():
    client = TestClient(app)
    # Missing required fields -> 422, not 404 — proves the route itself is still registered.
    response = client.post("/runs", json={})
    assert response.status_code == 422


def test_existing_skill_gap_endpoint_still_exists():
    client = TestClient(app)
    response = client.post("/skill-gap/runs", json={})
    assert response.status_code == 422
