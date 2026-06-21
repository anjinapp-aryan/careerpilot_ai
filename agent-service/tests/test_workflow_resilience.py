"""
Regression tests for the workflow resiliency fix.

Root cause (proven from agent-service container logs at 13:54:52, thread_id
a2237eb6-1a41-49d5-9215-e4f502bf49f0): Neon drops an idle Postgres connection
server-side; the next checkpoint read inside graph.invoke() raises
psycopg.OperationalError ("SSL SYSCALL error: EOF detected"); main.py's own
except-block recovery then called the unguarded graph.get_state() a second
time, which could fail with the same error class and escape the route
handler entirely as an unhandled ASGI 500 — the exact symptom reported
("Exception in ASGI application", truncated at starlette/routing.py:288).

These tests guard, without touching real Postgres or real AI providers:
  1. successful workflow                  -> test_start_run_success
  2. approval pause/resume                  -> test_start_run_interrupts_at_approval,
                                                test_resume_run_approved_completes
  3. DeepSeek failure -> Gemini             -> test_failover_deepseek_to_gemini
  4. Gemini failure -> Groq                 -> test_failover_gemini_to_groq
  5. Groq failure -> Qwen                   -> test_failover_groq_to_qwen
  6. Qwen failure -> graceful workflow error -> test_all_providers_exhausted_raises_runtime_error
  7. missing state key                      -> test_classify_handles_missing_state_keys
  8. invalid approval transition            -> test_resume_run_rejects_when_not_awaiting_approval
  9. agent timeout                          -> test_failover_on_provider_timeout
  10. database failure                      -> test_safe_get_state_degrades_to_none_after_db_failure,
                                                test_start_run_db_failure_returns_error_not_500

Run with:
    pip install -r requirements-dev.txt
    pytest tests/test_workflow_resilience.py -v
"""
from __future__ import annotations

import time

import pytest
from fastapi.testclient import TestClient

from app import main as main_module
from app.main import _classify, _safe_get_state, app
from app.workflow_ai_gateway import (
    ProviderStatus,
    WorkflowAIProvider,
    WorkflowAiGateway,
    reset_workflow_gateway,
)


# ---------------------------------------------------------------------------
# Shared fakes
# ---------------------------------------------------------------------------

class _FakeSnapshot:
    def __init__(self, values: dict, next_nodes: tuple = ()):
        self.values = values
        self.next = next_nodes


class _FakeGraph:
    """Stand-in for the compiled LangGraph graph used by main.py's routes."""

    def __init__(self, *, invoke_result=None, invoke_raises=None, get_state_result=None, get_state_raises=None):
        self._invoke_result = invoke_result
        self._invoke_raises = invoke_raises
        self._get_state_result = get_state_result
        self._get_state_raises = get_state_raises
        self.update_state_calls: list[dict] = []

    def invoke(self, _initial, config):
        if self._invoke_raises is not None:
            raise self._invoke_raises
        return self._invoke_result

    def get_state(self, _config):
        if self._get_state_raises is not None:
            raise self._get_state_raises
        return self._get_state_result

    def update_state(self, _config, values):
        self.update_state_calls.append(values)


@pytest.fixture
def client():
    return TestClient(app)


def _patch_graph(monkeypatch, fake_graph):
    monkeypatch.setattr(main_module, "get_compiled_graph", lambda: fake_graph)


# ---------------------------------------------------------------------------
# 1. Successful workflow
# ---------------------------------------------------------------------------

def test_start_run_success(client, monkeypatch):
    final_state = {
        "resume_text": "x",
        "target_role": "Engineer",
        "job_descriptions": [],
        "human_decision": "",
        "awaiting_human_approval": False,
        "agent_execution": [],
        "errors": [],
    }
    _patch_graph(monkeypatch, _FakeGraph(invoke_result=final_state))

    resp = client.post("/runs", json={
        "user_id": "u1", "org_id": "o1", "resume_text": "x", "target_role": "Engineer",
    })

    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "completed"
    assert body["state"]["target_role"] == "Engineer"


# ---------------------------------------------------------------------------
# 2. Approval pause/resume
# ---------------------------------------------------------------------------

def test_start_run_interrupts_at_approval(client, monkeypatch):
    """graph.invoke raising NodeInterrupt-equivalent + a genuine approval-gate
    snapshot must surface as status="interrupted", never status="error"."""
    paused_state = {
        "awaiting_human_approval": True,
        "human_decision": "",
        "errors": [],
    }
    fake_graph = _FakeGraph(
        invoke_raises=RuntimeError("paused for human approval"),
        get_state_result=_FakeSnapshot(paused_state, next_nodes=("human_approval",)),
    )
    _patch_graph(monkeypatch, fake_graph)

    resp = client.post("/runs", json={
        "user_id": "u1", "org_id": "o1", "resume_text": "x", "target_role": "Engineer",
    })

    assert resp.status_code == 200
    assert resp.json()["status"] == "interrupted"


def test_resume_run_approved_completes(client, monkeypatch):
    awaiting_snapshot = _FakeSnapshot({"awaiting_human_approval": True}, next_nodes=("human_approval",))
    completed_state = {"awaiting_human_approval": False, "human_decision": "approved", "errors": []}
    fake_graph = _FakeGraph(get_state_result=awaiting_snapshot, invoke_result=completed_state)
    _patch_graph(monkeypatch, fake_graph)

    resp = client.post("/runs/resume", json={
        "thread_id": "t1", "human_decision": "approved", "human_feedback": "",
    })

    assert resp.status_code == 200
    assert resp.json()["status"] == "completed"
    assert fake_graph.update_state_calls[0]["human_decision"] == "approved"


# ---------------------------------------------------------------------------
# 8. Invalid approval transition
# ---------------------------------------------------------------------------

def test_resume_run_rejects_when_not_awaiting_approval(client, monkeypatch):
    """A run that is NOT parked at human_approval (already decided, or never
    reached the gate) must 409, never silently flip status (the double-decision
    bug this guard exists to prevent)."""
    not_awaiting_snapshot = _FakeSnapshot({"human_decision": "approved"}, next_nodes=())
    fake_graph = _FakeGraph(get_state_result=not_awaiting_snapshot)
    _patch_graph(monkeypatch, fake_graph)

    resp = client.post("/runs/resume", json={
        "thread_id": "t1", "human_decision": "rejected", "human_feedback": "",
    })

    assert resp.status_code == 409


# ---------------------------------------------------------------------------
# 7. Missing state key
# ---------------------------------------------------------------------------

def test_classify_handles_missing_state_keys():
    """_classify must never KeyError when required keys are absent — it should
    degrade to a safe default ("completed") rather than crash the route."""
    assert _classify({}) == "completed"
    assert _classify({"awaiting_human_approval": True}) == "interrupted"
    assert _classify({"errors": ["boom"]}) == "error"


# ---------------------------------------------------------------------------
# 10. Database failure
# ---------------------------------------------------------------------------

class _FakeOperationalError(Exception):
    """Stand-in for psycopg.OperationalError without requiring psycopg installed."""


def test_safe_get_state_degrades_to_none_after_db_failure():
    """The exact second-order failure from the incident: graph.get_state() fails
    on every attempt (Neon connection dropped) — _safe_get_state must return
    None instead of letting the exception escape."""
    fake_graph = _FakeGraph(get_state_raises=_FakeOperationalError("SSL SYSCALL error: EOF detected"))

    result = _safe_get_state(fake_graph, {"configurable": {"thread_id": "t1"}})

    assert result is None


def test_start_run_db_failure_returns_error_not_500(client, monkeypatch):
    """graph.invoke() fails (DB drop) AND the recovery's graph.get_state() also
    fails (same Neon drop, second connection from the pool also stale) — the
    route must still return a structured 200/status=error, never an unhandled
    500 escaping past FastAPI (the exact bug from the incident)."""
    fake_graph = _FakeGraph(
        invoke_raises=_FakeOperationalError("SSL SYSCALL error: EOF detected"),
        get_state_raises=_FakeOperationalError("SSL SYSCALL error: EOF detected"),
    )
    _patch_graph(monkeypatch, fake_graph)

    resp = client.post("/runs", json={
        "user_id": "u1", "org_id": "o1", "resume_text": "x", "target_role": "Engineer",
    })

    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "error"
    assert any("Run failed" in e for e in body["state"]["errors"])


def test_get_run_returns_503_on_db_failure_not_404(client, monkeypatch):
    """A transiently-unavailable checkpoint store must not be reported as a
    nonexistent thread (404) — that would make the backend/UI wrongly treat a
    live run as permanently gone."""
    fake_graph = _FakeGraph(get_state_raises=_FakeOperationalError("SSL SYSCALL error: EOF detected"))
    _patch_graph(monkeypatch, fake_graph)

    resp = client.get("/runs/some-thread-id")

    assert resp.status_code == 503


def test_get_run_returns_404_for_genuinely_unknown_thread(client, monkeypatch):
    """LangGraph returns an empty StateSnapshot (not None) for an unknown
    thread_id — this must still map to 404, distinct from the 503 DB-failure case."""
    empty_snapshot = _FakeSnapshot({}, next_nodes=())
    fake_graph = _FakeGraph(get_state_result=empty_snapshot)
    _patch_graph(monkeypatch, fake_graph)

    resp = client.get("/runs/never-existed")

    assert resp.status_code == 404


# ---------------------------------------------------------------------------
# AI provider failover chain: DeepSeek -> Gemini -> Groq -> Qwen
# ---------------------------------------------------------------------------

class _StubProvider(WorkflowAIProvider):
    def __init__(self, name: str, *, raises: Exception | None = None, result: dict | None = None):
        self._name = name
        self._raises = raises
        self._result = result if result is not None else {"ok": True}
        self.calls = 0

    @property
    def name(self) -> str:
        return self._name

    def generate_structured_response(self, prompt, schema, *, system=None, timeout_seconds=15.0):
        self.calls += 1
        if self._raises is not None:
            raise self._raises
        return self._result


@pytest.fixture(autouse=True)
def _reset_gateway_singleton():
    reset_workflow_gateway()
    yield
    reset_workflow_gateway()


def _make_gateway(deepseek=None, gemini=None, groq=None, qwen=None):
    return WorkflowAiGateway(
        deepseek_provider=deepseek,
        gemini_provider=gemini or _StubProvider("gemini"),
        groq_provider=groq,
        qwen_provider=qwen,
    )


def test_provider_chain_order_is_deepseek_gemini_groq_qwen():
    deepseek, gemini, groq, qwen = (
        _StubProvider("deepseek"), _StubProvider("gemini"), _StubProvider("groq"), _StubProvider("qwen"),
    )
    gateway = _make_gateway(deepseek=deepseek, gemini=gemini, groq=groq, qwen=qwen)

    assert [p.name for p in gateway._providers] == ["deepseek", "gemini", "groq", "qwen"]


def test_failover_deepseek_to_gemini():
    deepseek = _StubProvider("deepseek", raises=RuntimeError("nvidia 503"))
    gemini = _StubProvider("gemini", result={"served_by": "gemini"})
    gateway = _make_gateway(deepseek=deepseek, gemini=gemini)

    result = gateway.generate_structured_response("p", {"properties": {}}, stage="resume_intelligence")

    assert result == {"served_by": "gemini"}
    assert deepseek.calls == 1
    assert gemini.calls == 1


def test_failover_gemini_to_groq():
    deepseek = _StubProvider("deepseek", raises=RuntimeError("nvidia 503"))
    gemini = _StubProvider("gemini", raises=RuntimeError("gemini 500"))
    groq = _StubProvider("groq", result={"served_by": "groq"})
    gateway = _make_gateway(deepseek=deepseek, gemini=gemini, groq=groq)

    result = gateway.generate_structured_response("p", {"properties": {}}, stage="job_discovery")

    assert result == {"served_by": "groq"}


def test_failover_groq_to_qwen():
    deepseek = _StubProvider("deepseek", raises=RuntimeError("nvidia 503"))
    gemini = _StubProvider("gemini", raises=RuntimeError("gemini 500"))
    groq = _StubProvider("groq", raises=RuntimeError("groq 500"))
    qwen = _StubProvider("qwen", result={"served_by": "qwen"})
    gateway = _make_gateway(deepseek=deepseek, gemini=gemini, groq=groq, qwen=qwen)

    result = gateway.generate_structured_response("p", {"properties": {}}, stage="ats_optimization")

    assert result == {"served_by": "qwen"}


def test_all_providers_exhausted_raises_runtime_error():
    """Qwen failure (last in the chain) must surface as a single RuntimeError —
    a graceful, catchable workflow error — never an unhandled crash."""
    deepseek = _StubProvider("deepseek", raises=RuntimeError("nvidia 503"))
    gemini = _StubProvider("gemini", raises=RuntimeError("gemini 500"))
    groq = _StubProvider("groq", raises=RuntimeError("groq 500"))
    qwen = _StubProvider("qwen", raises=RuntimeError("qwen 500"))
    gateway = _make_gateway(deepseek=deepseek, gemini=gemini, groq=groq, qwen=qwen)

    with pytest.raises(RuntimeError, match="All providers failed for stage 'career_strategy'"):
        gateway.generate_structured_response("p", {"properties": {}}, stage="career_strategy")


# ---------------------------------------------------------------------------
# 9. Agent timeout
# ---------------------------------------------------------------------------

def test_failover_on_provider_timeout():
    """A provider that times out must be treated like any other provider
    failure and trigger failover, not hang the whole stage."""
    class _TimeoutError(Exception):
        pass

    deepseek = _StubProvider("deepseek", raises=_TimeoutError("timed out after 15s"))
    gemini = _StubProvider("gemini", result={"served_by": "gemini"})
    gateway = _make_gateway(deepseek=deepseek, gemini=gemini)

    result = gateway.generate_structured_response("p", {"properties": {}}, stage="interview_prep")

    assert result == {"served_by": "gemini"}


def test_quota_exceeded_provider_is_skipped_on_subsequent_calls():
    """A 429/quota error must lock the provider out (circuit breaker) instead
    of retrying it on every subsequent stage call."""
    deepseek = _StubProvider("deepseek", raises=RuntimeError("429 quota exceeded"))
    gemini = _StubProvider("gemini", result={"served_by": "gemini"})
    gateway = _make_gateway(deepseek=deepseek, gemini=gemini)

    gateway.generate_structured_response("p", {"properties": {}}, stage="stage1")
    assert gateway._health.get_status("deepseek") == ProviderStatus.QUOTA_EXCEEDED

    gateway.generate_structured_response("p", {"properties": {}}, stage="stage2")

    # deepseek must NOT have been retried on stage2 — only the first call counts.
    assert deepseek.calls == 1
    assert gemini.calls == 2


def test_rate_limit_lockout_is_short_not_30_minutes():
    """Regression: a transient NVIDIA/Gemini 429 (per-minute rate limit) must bench
    the primary for ~60s, NOT 30 minutes. A long lockout on a process-wide singleton
    forces EVERY subsequent workflow run onto the fallback (the exact 'Gemini used
    instead of DeepSeek' incident). Pin the TTL so it can't silently regress to 1800s.
    """
    from app.workflow_ai_gateway import ProviderHealth

    health = ProviderHealth()
    health.mark_quota_exceeded()

    # Must be a short, transient-rate-limit window — not the old 30-minute blackout.
    assert health.ttl_seconds <= 120.0, (
        f"rate-limit lockout is {health.ttl_seconds}s; a transient 429 must not "
        f"blackball the primary provider for minutes across all workflow runs"
    )

    # And it must actually expire after that window (simulate time passing).
    health.last_checked_at = time.time() - (health.ttl_seconds + 1)
    assert health.is_expired(), "lockout must lapse so the primary is re-probed next run"
