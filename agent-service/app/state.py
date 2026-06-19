"""Shared workflow state across all LangGraph nodes."""
from __future__ import annotations

from typing import Annotated, Any, TypedDict
import operator


class CareerState(TypedDict, total=False):
    # Inputs
    user_id: str
    org_id: str
    resume_text: str
    target_role: str
    target_seniority: str
    target_locations: list[str]
    job_descriptions: list[dict]  # [{id, title, company, description, location, salary?}]

    # Resume Intelligence outputs
    candidate_profile: dict
    resume_score: int
    extracted_skills: list[str]

    # Job Discovery outputs
    ranked_jobs: list[dict]      # [{job_id, match_score, missing_skills, rationale}]
    job_match_score: int

    # ATS outputs
    ats_score: int
    missing_keywords: list[str]
    ats_optimization_plan: list[str]

    # Interview Prep outputs
    interview_plan: dict
    interview_readiness_score: int

    # Career Strategy
    career_roadmap: dict
    skill_gaps: list[str]

    # Salary  (NB: key must NOT match the "salary_intelligence" node name —
    # LangGraph forbids a state key that collides with a node id)
    salary_insights: dict

    # Human approval gate
    awaiting_human_approval: bool
    human_decision: str   # "approved" | "rejected" | ""
    human_feedback: str

    # Application Tracking
    tracked_application: dict

    # Cross-cutting
    errors: Annotated[list[str], operator.add]
    cost_usd: float
