"""
Regression tests for app.graph._instrument (the per-stage telemetry wrapper).

Root-cause guarded here:
    The wrapper logged with ``extra={"name": display_name, ...}``. ``name`` is a
    RESERVED ``logging.LogRecord`` attribute, so the stdlib raised
    ``KeyError("Attempt to overwrite 'name' in LogRecord")`` on the FIRST log
    call inside every wrapped node. That crashed each workflow at stage 1
    (resume_intelligence) before any agent produced output — surfacing as an
    immediate ERROR run with all stage outputs missing.

These tests force the ``app.graph`` logger to DEBUG so ``log.info`` actually
builds a LogRecord (``isEnabledFor`` gates ``makeRecord``); at the default
WARNING level the buggy ``log.info`` would be skipped and the regression hidden.

Run with:
    pip install -r requirements-dev.txt
    pytest tests/test_graph_instrumentation.py -v
"""
from __future__ import annotations

import logging

import pytest

from app.graph import _instrument

# Reserved LogRecord attributes that must NEVER appear as keys in an `extra=` dict.
_RESERVED_LOGRECORD_KEYS = set(vars(logging.makeLogRecord({})).keys()) | {"message", "asctime"}


@pytest.fixture(autouse=True)
def _force_graph_logger_verbose():
    """
    Make the app.graph logger emit at INFO so the wrapper's log.info() calls
    actually construct a LogRecord. Without this, isEnabledFor(INFO) is False at
    the default WARNING level and makeRecord — where the reserved-key KeyError is
    raised — never runs, so the regression would slip through green.
    """
    lg = logging.getLogger("app.graph")
    prev_level = lg.level
    handler = logging.StreamHandler()
    lg.addHandler(handler)
    lg.setLevel(logging.DEBUG)
    try:
        yield
    finally:
        lg.removeHandler(handler)
        lg.setLevel(prev_level)


def test_instrument_does_not_crash_on_reserved_logrecord_key():
    """The exact regression: the wrapper must log + run the node without raising
    KeyError('Attempt to overwrite ...')."""
    def node(_state):
        return {"resume_score": 42, "extracted_skills": ["java"]}

    wrapped = _instrument("resume_intelligence", "Resume Intelligence", node)

    result = wrapped({"resume_text": "a real resume"})  # must NOT raise

    assert result["resume_score"] == 42
    timeline = result["agent_execution"]
    assert len(timeline) == 1
    assert timeline[0]["stage"] == "resume_intelligence"
    assert timeline[0]["status"] == "COMPLETED"
    # The telemetry entry itself (a plain dict, not a LogRecord) intentionally
    # keeps "name" — the backend timeline reads it.
    assert timeline[0]["name"] == "Resume Intelligence"


def test_instrument_records_failed_when_node_returns_errors():
    """A node that guards its body and returns {"errors": [...]} must be recorded
    as FAILED in the timeline (not COMPLETED), with the error surfaced."""
    def failing_node(_state):
        return {"errors": ["resume_intelligence: boom"], "resume_score": 0}

    wrapped = _instrument("resume_intelligence", "Resume Intelligence", failing_node)

    result = wrapped({})  # must NOT raise (exercises the success-with-errors path)

    entry = result["agent_execution"][0]
    assert entry["status"] == "FAILED"
    assert entry["error"] == "resume_intelligence: boom"


def test_instrument_reraises_node_exception_without_logging_fault():
    """When the wrapped node itself raises, the wrapper logs via the stage_failed
    branch (which also passed a reserved key in the original bug) and re-raises
    the ORIGINAL error — never a secondary KeyError from logging."""
    def boom(_state):
        raise ValueError("kaboom")

    wrapped = _instrument("job_discovery", "Job Discovery", boom)

    with pytest.raises(ValueError, match="kaboom"):
        wrapped({})


def test_instrument_extra_dicts_use_no_reserved_logrecord_keys():
    """Belt-and-braces: capture every record the wrapper emits and assert none of
    them carry a reserved attribute injected via `extra=` (guards stage_start,
    stage_end, and stage_failed against future reserved-key regressions)."""
    captured: list[logging.LogRecord] = []

    class _Capture(logging.Handler):
        def emit(self, record):  # noqa: D401
            captured.append(record)

    lg = logging.getLogger("app.graph")
    cap = _Capture()
    lg.addHandler(cap)
    try:
        # success path: emits stage_start + stage_end
        _instrument("ats_optimization", "ATS Optimization", lambda s: {"ats_score": 80})({})
        # failure path: emits stage_start + stage_failed, then re-raises
        with pytest.raises(RuntimeError):
            _instrument("career_strategy", "Career Strategy",
                        lambda s: (_ for _ in ()).throw(RuntimeError("x")))({})
    finally:
        lg.removeHandler(cap)

    assert captured, "expected the wrapper to emit log records"
    for rec in captured:
        # The wrapper attributes (event/stage/display_name/...) must be present
        # without colliding with reserved LogRecord fields.
        assert getattr(rec, "display_name", None) is not None
        # `name` on the record is the logger name, never the stage display name.
        assert rec.name == "app.graph"
