"""Shared workflow state for the Skill Gap Intelligence Workflow.

Deliberately a separate TypedDict from `app.state.CareerState` — this is a different graph with a
different shape, not an extension of the main career graph's state. Field names are snake_case
(Python convention); the Java Control Plane's opaque `WorkflowState.inputs()` map is translated
into this shape only by `app/skillgap/router.py`, never elsewhere.
"""
from __future__ import annotations

from typing import Annotated, TypedDict
import operator


class SkillGapState(TypedDict, total=False):
    # --- Inputs (from the Java Control Plane, opaque business payload) ---
    mission_id: str
    user_id: str
    workflow_id: str
    execution_id: str
    correlation_id: str

    mission_statement: str
    target_role: str
    target_level: str
    target_countries: list[str]
    timeline_months: int
    current_skills: list[str]
    skills_to_acquire: list[str]
    experience_years: int
    resume_text: str

    # --- Mission Context Agent output ---
    mission_context: dict  # {target_role, target_level, target_countries, timeline_months, mission_summary}

    # --- Resume Intelligence Agent output ---
    candidate_profile: dict  # {skills, technologies, certifications, experience_years, strengths}

    # --- Market Intelligence Agent output ---
    market_expectations: dict  # {expected_skills: [{skill, priority, reason}], country_notes, industry_trends}

    # --- Skill Gap Agent output ---
    critical_skill_gaps: list[dict]   # [{skill, priority, reason}]
    important_skill_gaps: list[dict]
    optional_skill_gaps: list[dict]

    # --- Learning Roadmap Agent output ---
    recommended_learning_roadmap: list[dict]  # [{skill, priority, estimated_duration_weeks, difficulty, depends_on, business_impact}]
    estimated_completion_months: int

    # --- Mission Readiness Agent output ---
    readiness_score: int
    confidence: float
    mission_progress: float
    strengths: list[str]
    risks: list[str]
    recommendations: list[str]

    # --- Cross-cutting ---
    # Uses operator.add so LangGraph appends across nodes instead of overwriting —
    # same convention as CareerState.errors. A non-empty list never means the run
    # crashed; every agent below degrades to a safe partial result and records
    # here instead of raising (see each agent's own docstring).
    errors: Annotated[list[str], operator.add]
