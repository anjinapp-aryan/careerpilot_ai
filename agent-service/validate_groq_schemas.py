"""
One-off conformance probe: does Groq's json_object mode actually return output
that matches the REAL per-stage workflow schemas (not just a trivial {"reply"})?

For each agent it imports the actual SCHEMA + SYSTEM, sends a realistic prompt to
GroqProvider, and checks that every `required` top-level key is present with a
plausible type. This is the honest test of "will Groq break mid-workflow?".

Run from agent-service/:  python validate_groq_schemas.py
"""
from __future__ import annotations

import os

from dotenv import load_dotenv

_root_env = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), ".env")
if os.path.exists(_root_env):
    load_dotenv(_root_env, override=False)

from app.config import settings
from app.workflow_ai_gateway import GroqProvider
from app.agents import (
    resume_intelligence, job_discovery, ats_optimization,
    interview_prep, career_strategy, salary_intelligence, application_tracking,
)

_RESUME = (
    "Senior backend engineer, 8 years. Java, Spring Boot, Kafka, PostgreSQL, AWS. "
    "Led a payments platform handling 2M tx/day; cut p99 latency 40%. Mentored 4 engineers."
)

# (label, schema, system, realistic prompt) per stage.
CASES = [
    ("resume_intelligence", resume_intelligence.SCHEMA, resume_intelligence.SYSTEM,
     f"Analyze this resume; extract candidate_profile, extracted_skills, resume_score (0-100).\n\nRESUME:\n{_RESUME}"),
    ("job_discovery", job_discovery.SCHEMA, job_discovery.SYSTEM,
     f"Rank these jobs for the candidate and give job_match_score.\n\nCANDIDATE:\n{_RESUME}\n\nJOBS:\n- Staff Engineer at Acme (Java, Kafka, distributed systems)"),
    ("ats_optimization", ats_optimization.SCHEMA, ats_optimization.SYSTEM,
     f"Score ATS fit (ats_score), list missing_keywords and an ats_optimization_plan.\n\nRESUME:\n{_RESUME}\n\nTARGET ROLE: Staff Engineer"),
    ("interview_prep", interview_prep.SCHEMA, interview_prep.SYSTEM,
     f"Build an interview_plan and interview_readiness_score for this candidate.\n\nRESUME:\n{_RESUME}\n\nTARGET ROLE: Staff Engineer"),
    ("career_strategy", career_strategy.SCHEMA, career_strategy.SYSTEM,
     f"Build a career_roadmap and list skill_gaps toward Principal Engineer.\n\nRESUME:\n{_RESUME}"),
    ("salary_intelligence", salary_intelligence.SCHEMA, salary_intelligence.SYSTEM,
     f"Estimate salary_insights (currency, p25/p50/p75/p90, negotiation_strategy) for a Staff Engineer.\n\nCANDIDATE:\n{_RESUME}"),
    ("application_tracking", application_tracking.SCHEMA, application_tracking.SYSTEM,
     f"Produce a tracked_application (primary_job_id, status, next_actions, reminders).\n\nCANDIDATE:\n{_RESUME}\n\nJOB: job-123 Staff Engineer at Acme"),
]

_JSON_TYPE = {
    "object": dict, "array": list, "string": str,
    "integer": int, "number": (int, float), "boolean": bool,
}


def _check(schema: dict, obj) -> list[str]:
    """Return a list of conformance problems for the required top-level keys."""
    problems = []
    if not isinstance(obj, dict):
        return [f"top-level is {type(obj).__name__}, expected object"]
    for key in schema.get("required", []):
        if key not in obj:
            problems.append(f"MISSING required key '{key}'")
            continue
        expected = schema["properties"].get(key, {}).get("type")
        py = _JSON_TYPE.get(expected)
        if py and not isinstance(obj[key], py):
            problems.append(f"key '{key}' is {type(obj[key]).__name__}, expected {expected}")
    return problems


def main() -> int:
    if not settings.groq_api_key:
        print("GROQ_API_KEY not configured - cannot test.")
        return 2
    groq = GroqProvider(settings.groq_api_key, settings.groq_base_url, settings.groq_model)

    print("=" * 74)
    print(f"GROQ SCHEMA CONFORMANCE - model={settings.groq_model}")
    print("=" * 74)
    any_fail = False
    for label, schema, system, prompt in CASES:
        try:
            result = groq.generate_structured_response(prompt, schema, system=system, timeout_seconds=30.0)
            problems = _check(schema, result)
            if problems:
                any_fail = True
                print(f"  [{label:21}] PARTIAL - keys={list(result.keys())}")
                for p in problems:
                    print(f"      - {p}")
            else:
                print(f"  [{label:21}] OK - all required keys present & typed: {schema.get('required')}")
        except Exception as e:
            any_fail = True
            print(f"  [{label:21}] FAIL - {type(e).__name__}: {str(e)[:120]}")
    print("=" * 74)
    print("Note: agents read every field defensively via result.get(key, default),")
    print("so a PARTIAL degrades to empty data for that field - it does NOT crash the stage.")
    print("=" * 74)
    return 1 if any_fail else 0


if __name__ == "__main__":
    raise SystemExit(main())
