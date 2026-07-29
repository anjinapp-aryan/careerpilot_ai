"""Tests for the shared structured-AI-invocation helper (app.agent_support), Phase 10A."""
from __future__ import annotations

import logging

import pytest

import app.agent_support as agent_support_module
from app.agent_support import call_structured_agent

log = logging.getLogger("test_agent_support")


def test_call_structured_agent_returns_the_mapped_success_result(monkeypatch: pytest.MonkeyPatch):
    class _FakeGateway:
        def generate_structured_response(self, prompt, schema, *, system=None, stage="unknown"):
            assert stage == "my_stage"
            return {"value": 42}

    monkeypatch.setattr(agent_support_module, "get_workflow_ai_gateway", lambda: _FakeGateway())

    result = call_structured_agent(
        stage="my_stage", log_name="my_agent", prompt="p", schema={}, system="s",
        on_success=lambda r: {"doubled": r["value"] * 2}, fallback={"doubled": 0}, log=log,
    )

    assert result == {"doubled": 84}


def test_call_structured_agent_degrades_to_fallback_merged_with_errors(monkeypatch: pytest.MonkeyPatch):
    class _RaisingGateway:
        def generate_structured_response(self, *args, **kwargs):
            raise RuntimeError("provider down")

    monkeypatch.setattr(agent_support_module, "get_workflow_ai_gateway", lambda: _RaisingGateway())

    result = call_structured_agent(
        stage="my_stage", log_name="my_agent", prompt="p", schema={}, system="s",
        on_success=lambda r: {"doubled": r["value"] * 2}, fallback={"doubled": 0}, log=log,
    )

    assert result["doubled"] == 0
    assert result["errors"] == ["my_agent: provider down"]


def test_call_structured_agent_never_raises_past_itself(monkeypatch: pytest.MonkeyPatch):
    def _raise():
        raise ValueError("gateway construction failed")

    monkeypatch.setattr(agent_support_module, "get_workflow_ai_gateway", _raise)

    # Must not raise — the whole point is that a LangGraph node calling this never crashes the graph.
    result = call_structured_agent(
        stage="s", log_name="agent", prompt="p", schema={}, system="sys",
        on_success=lambda r: r, fallback={}, log=log,
    )

    assert "errors" in result
