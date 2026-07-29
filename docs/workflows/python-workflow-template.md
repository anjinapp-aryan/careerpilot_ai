# Python Workflow Template

Copy-pasteable skeleton for a new CareerPilot AI workflow's Python side. Replace
`<workflow>`/`<Workflow>`/`<WORKFLOW_TYPE>`/`<agent_name>` throughout. See
`docs/architecture/WORKFLOW_STANDARD.md` for the rationale.

**Reminder**: this template registers into the existing generic dispatcher — it does **not**
create a new `router.py`/FastAPI route, unlike Skill Gap Intelligence's historical shape.

---

## 1. `app/<workflow>/__init__.py`

```python
"""<Workflow> Intelligence Workflow — answers "<the business question>".

A standalone, additive LangGraph graph (own state, own agents) registered into the shared
Workflow Dispatcher (app/dispatcher/) — no dedicated endpoint, per the Workflow Development
Standard (docs/architecture/WORKFLOW_STANDARD.md). Never modifies app/graph.py, app/state.py, or
any other existing workflow's files.
"""
```

## 2. `app/<workflow>/state.py`

```python
"""Shared workflow state for the <Workflow> Intelligence Workflow."""
from __future__ import annotations

from typing import Annotated, TypedDict
import operator


class <Workflow>State(TypedDict, total=False):
    # --- Mission metadata ---
    mission_id: str
    user_id: str

    # --- Execution metadata ---
    workflow_id: str
    execution_id: str
    correlation_id: str

    # --- Business inputs (from the Java Control Plane) ---
    # target_role: str
    # ... workflow-specific fields ...

    # --- <agent_name_1> Agent output ---
    # ...

    # --- <agent_name_2> Agent output ---
    # ...

    # --- Cross-cutting ---
    errors: Annotated[list[str], operator.add]
```

## 3. `app/<workflow>/agents/__init__.py`

Empty file (matches every existing agents package).

## 4. `app/<workflow>/agents/<agent_name>.py` — deterministic node (no AI call)

```python
"""<Agent Name> Agent — <what it does>.

Deterministic (no AI call): <why — e.g. "the data is already structured">.
"""
from __future__ import annotations

import logging

from ..state import <Workflow>State

log = logging.getLogger(__name__)


def <agent_name>_node(state: <Workflow>State) -> dict:
    # ... pure computation over already-present state fields ...
    log.info("<agent_name>: stage completed")
    return {}  # partial state update
```

## 5. `app/<workflow>/agents/<agent_name>.py` — AI-calling node

```python
"""<Agent Name> Agent — <what it does>."""
from __future__ import annotations

import json
import logging

from ..state import <Workflow>State
from ...agent_support import call_structured_agent

log = logging.getLogger(__name__)

SYSTEM = "You are a <role>. Output strict JSON only."

SCHEMA = {
    "type": "object",
    "properties": {
        # ...
    },
    "required": [],
}


def <agent_name>_node(state: <Workflow>State) -> dict:
    prompt = (
        "..."
        f"{json.dumps(state.get('some_input', {}))}"
    )

    def on_success(result: dict) -> dict:
        return {"<output_field>": result.get("<output_field>") or <default>}

    return call_structured_agent(
        stage="<workflow>_<agent_name>", log_name="<agent_name>",
        prompt=prompt, schema=SCHEMA, system=SYSTEM, on_success=on_success,
        fallback={"<output_field>": <safe_default>},
        log=log,
    )
```

## 6. `app/<workflow>/graph.py`

```python
"""LangGraph workflow: <Workflow> Intelligence — linear pipeline, no checkpointing."""
from __future__ import annotations

from functools import lru_cache

from langgraph.graph import END, START, StateGraph

from .agents.<agent_name_1> import <agent_name_1>_node
from .agents.<agent_name_2> import <agent_name_2>_node
from .state import <Workflow>State


def _build_<workflow>_graph() -> StateGraph:
    g = StateGraph(<Workflow>State)
    # NB: if a node's natural name collides with a state key it writes, suffix the NODE id with
    # "_agent" — never rename the state field. See the Standard's naming-collision rule.
    g.add_node("<agent_name_1>", <agent_name_1>_node)
    g.add_node("<agent_name_2>", <agent_name_2>_node)

    g.add_edge(START, "<agent_name_1>")
    g.add_edge("<agent_name_1>", "<agent_name_2>")
    g.add_edge("<agent_name_2>", END)
    return g


@lru_cache(maxsize=1)
def get_compiled_<workflow>_graph():
    return _build_<workflow>_graph().compile()
```

## 7. `app/<workflow>/registration.py` — the entire dispatcher integration

```python
"""Registers the <Workflow> Intelligence graph into the generic dispatcher (Phase 10A pattern)."""
from __future__ import annotations

from typing import Any

from .graph import get_compiled_<workflow>_graph
from .state import <Workflow>State
from ..dispatcher.registry import WorkflowDispatchRegistry, WorkflowRegistration

WORKFLOW_ID = "<WORKFLOW_TYPE>_V1"

_OUTPUT_FIELDS = (
    # "<output_field_1>", "<output_field_2>", ...
)


def _state_mapper(req: Any, execution_id: str, correlation_id: str) -> <Workflow>State:
    inputs = req.inputs or {}
    return {
        "mission_id": req.mission_id,
        "user_id": req.user_id,
        "workflow_id": WORKFLOW_ID,
        "execution_id": execution_id,
        "correlation_id": correlation_id,
        # ... map inputs.get(...) into business-input fields ...
        "errors": [],
    }


def _output_mapper(final_state: dict) -> dict:
    return {field: final_state.get(field) for field in _OUTPUT_FIELDS}


def register_<workflow>_workflow(registry: WorkflowDispatchRegistry) -> None:
    registry.register(WorkflowRegistration(
        workflow_id=WORKFLOW_ID,
        graph_factory=get_compiled_<workflow>_graph,
        state_mapper=_state_mapper,
        output_mapper=_output_mapper,
    ))
```

## 8. `app/main.py` change — the entire footprint

```python
from .<workflow>.registration import register_<workflow>_workflow
# ...
# inside the existing Phase 10A dispatcher bootstrap block, alongside the other registrations:
register_<workflow>_workflow(get_dispatch_registry())
```

## 9. `tests/test_<workflow>_workflow.py` skeleton

```python
"""Tests for the <Workflow> Intelligence Workflow (app.<workflow>.*)."""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.<workflow>.graph import get_compiled_<workflow>_graph
from app.<workflow>.registration import WORKFLOW_ID


def test_graph_compiles_all_expected_nodes():
    graph = get_compiled_<workflow>_graph()
    node_names = set(graph.get_graph().nodes.keys())
    for expected in ("<agent_name_1>", "<agent_name_2>"):
        assert expected in node_names


def test_workflow_is_registered_at_startup():
    from app.dispatcher.registry import get_dispatch_registry
    assert WORKFLOW_ID in get_dispatch_registry().list_ids()


def test_end_to_end_via_the_generic_dispatcher(monkeypatch: pytest.MonkeyPatch):
    import app.agent_support as agent_support_module

    class _FakeGateway:
        def generate_structured_response(self, prompt, schema, *, system=None, stage="unknown"):
            return {}  # ... canned per-stage responses ...

    monkeypatch.setattr(agent_support_module, "get_workflow_ai_gateway", lambda: _FakeGateway())

    client = TestClient(app)
    response = client.post(f"/workflows/{WORKFLOW_ID}/runs", json={
        "mission_id": "m1", "user_id": "u1", "inputs": {},
    })

    assert response.status_code == 200
    assert response.json()["status"] == "completed"
```

## 10. Wiring checklist

- [ ] `app/<workflow>/` package created (state/graph/registration/agents)
- [ ] `app/main.py` gained one import + one `register_<workflow>_workflow(...)` call — nothing else
- [ ] No new `router.py`, no new `APIRouter`, no new route in `main.py`
- [ ] `pytest` full suite green
