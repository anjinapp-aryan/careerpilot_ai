"""In-process registry of dispatchable workflows.

A `WorkflowRegistration` is pure metadata + two small mapping functions — registering one never
executes anything (same "registering never means executing" discipline the Java-side Workflow
Registry, Phase 4, already established). The registry itself is a plain dict behind a lock; no
database, no new table — this mirrors the Java `InMemoryExecutionHistory`/`InMemoryWorkflowMetrics`
pattern of "a handful of entries never needs more than an in-memory structure."
"""
from __future__ import annotations

import threading
from dataclasses import dataclass
from typing import Any, Callable


@dataclass(frozen=True)
class WorkflowRegistration:
    workflow_id: str
    graph_factory: Callable[[], Any]
    # (request, execution_id, correlation_id) -> initial graph state. The dispatcher — not the
    # raw request — is the source of truth for execution_id/correlation_id, since it generates
    # them when the caller omits them; passing the already-resolved values avoids every
    # registration having to re-implement that "or uuid4()" fallback itself.
    state_mapper: Callable[[Any, str, str], dict]
    output_mapper: Callable[[dict], dict]
    error_key: str = "errors"


class WorkflowNotRegisteredError(KeyError):
    pass


class WorkflowDispatchRegistry:
    def __init__(self) -> None:
        self._workflows: dict[str, WorkflowRegistration] = {}
        self._lock = threading.Lock()

    def register(self, registration: WorkflowRegistration) -> None:
        with self._lock:
            self._workflows[registration.workflow_id] = registration

    def get(self, workflow_id: str) -> WorkflowRegistration:
        with self._lock:
            registration = self._workflows.get(workflow_id)
        if registration is None:
            raise WorkflowNotRegisteredError(workflow_id)
        return registration

    def list_ids(self) -> list[str]:
        with self._lock:
            return sorted(self._workflows.keys())


_registry = WorkflowDispatchRegistry()


def get_dispatch_registry() -> WorkflowDispatchRegistry:
    """Return the process-wide dispatch registry singleton."""
    return _registry
