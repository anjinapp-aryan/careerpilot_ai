"""Human Approval Agent — interrupts the graph for human review."""
from __future__ import annotations

from langgraph.errors import NodeInterrupt

from ..state import CareerState


def human_approval_node(state: CareerState) -> dict:
    decision = state.get("human_decision", "")
    if decision in ("approved", "rejected"):
        return {"awaiting_human_approval": False}

    # Surface key artifacts for the reviewer; LangGraph will pause here until the
    # caller resumes the thread with an updated state containing human_decision.
    raise NodeInterrupt(
        "Awaiting human approval. Review resume_score, ats_score, ranked_jobs, "
        "interview_plan, career_roadmap, salary_intelligence — then resume with "
        "human_decision='approved' or 'rejected'."
    )
