"""Shared structured-AI-invocation helper for LangGraph agent nodes — Phase 10A.

Extracted from the identical try/except/log/fallback shape repeated across
`app/agents/career_strategy.py` and three of `app/skillgap/agents/*.py`. This helper only removes
duplicated infrastructure — it never decides what an agent asks for or how to interpret a
successful result: each agent still owns its own `SYSTEM`/`SCHEMA`/prompt text and its own
success-path field mapping (`on_success`). Behavior-preserving by construction: every call site
this phase migrates keeps its exact original log message wording, error-message prefix, and
fallback shape — see each agent's own diff for the one-to-one mapping.
"""
from __future__ import annotations

import logging
from typing import Any, Callable

from .workflow_ai_gateway import get_workflow_ai_gateway


def call_structured_agent(
    *,
    stage: str,
    log_name: str,
    prompt: str,
    schema: dict[str, Any],
    system: str,
    on_success: Callable[[dict[str, Any]], dict[str, Any]],
    fallback: dict[str, Any],
    log: logging.Logger,
) -> dict[str, Any]:
    """
    Calls the AI Gateway for one structured-JSON agent stage and returns a LangGraph node's
    partial state update.

    Args:
        stage: the AI Gateway's own stage-attribution key (used for provider health/logging
            inside `WorkflowAiGateway` — may differ from `log_name`, matching each agent's
            existing convention where the gateway stage is namespaced but log lines aren't).
        log_name: the label used in this node's own `stage_started`/`stage_failed`/error-prefix
            log lines — preserved exactly as each migrated agent already logged it.
        on_success: maps the raw gateway result dict into this node's return dict. The helper
            never applies its own defaulting — that stays the agent's responsibility.
        fallback: merged with `{"errors": [...]}` and returned verbatim if the call raises.
    """
    try:
        log.info(f"{log_name}: stage started")
        gateway = get_workflow_ai_gateway()
        result = gateway.generate_structured_response(prompt, schema, system=system, stage=stage)
        mapped = on_success(result)
        log.info(f"{log_name}: stage completed successfully")
        return mapped
    except Exception as e:  # noqa: BLE001
        log.error(f"{log_name}: stage failed", extra={"error": str(e)}, exc_info=True)
        return {"errors": [f"{log_name}: {e}"], **fallback}
