"""Generic Workflow Dispatcher — Phase 10A.

Replaces the one-workflow assumption `POST /runs` was built around: any workflow that registers
itself here becomes reachable at `POST /workflows/{workflow_id}/runs` without a bespoke endpoint
or a bespoke Java client. `POST /runs` (the main career graph) and `POST /skill-gap/runs` are
untouched — this is a new, additive path, not a replacement.

Each workflow owns its own `StateGraph` (see `app/graph.py`, `app/skillgap/graph.py`) — this
package never builds a graph itself, it only routes a request to the correct pre-built one via a
`WorkflowRegistration` (state_mapper in, output_mapper out) and standardizes the response envelope
(status/executionId/correlationId/durationMs/output/errors) every workflow returns.
"""
