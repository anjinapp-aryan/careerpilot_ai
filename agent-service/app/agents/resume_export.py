"""Resume Export Agent — rewrites the resume applying the ATS optimization plan.

Runs only on the RESUME_OPTIMIZATION workflow path, after human approval. Produces
a fully rewritten, ATS-optimized resume (structured sections + full markdown) plus
an estimated post-optimization ATS score. The backend renders `optimized_resume`
into a downloadable DOCX/TXT and stores it as a new resume version.
"""
from __future__ import annotations

import json
import logging

from ..workflow_ai_gateway import get_workflow_ai_gateway
from ..state import CareerState

log = logging.getLogger(__name__)

SYSTEM = (
    "You are an elite executive resume writer and ATS optimization expert. You rewrite "
    "resumes to maximize ATS compatibility and recruiter impact while preserving every "
    "factual claim — never invent employers, titles, dates, or achievements. You output "
    "strict JSON only."
)

SCHEMA = {
    "type": "object",
    "properties": {
        "optimized_resume": {
            "type": "object",
            "properties": {
                "executive_summary": {"type": "string"},
                "professional_experience": {"type": "array", "items": {"type": "string"}},
                "skills_section": {"type": "array", "items": {"type": "string"}},
                "full_markdown": {"type": "string"},
            },
            "required": ["executive_summary", "professional_experience", "skills_section", "full_markdown"],
        },
        "ats_after": {"type": "integer"},
    },
    "required": ["optimized_resume", "ats_after"],
}


def resume_export_node(state: CareerState) -> dict:
    resume = state.get("resume_text", "")
    ats_before = int(state.get("ats_score", 0) or 0)
    if not resume.strip():
        log.warning("resume_export: resume_text is empty")
        return {
            "errors": ["resume_export: resume_text is empty"],
            "optimized_resume": {},
            "ats_before": ats_before,
            "ats_after": ats_before,
        }

    context = {
        "candidate_profile": state.get("candidate_profile") or {},
        "extracted_skills": state.get("extracted_skills") or [],
        "missing_keywords": state.get("missing_keywords") or [],
        "ats_optimization_plan": state.get("ats_optimization_plan") or [],
        "optimization_mode": state.get("optimization_mode") or "generic_ats",
        "ats_before": ats_before,
    }

    prompt = (
        "Rewrite the resume below into an ATS-optimized version targeting the given mode. "
        "Apply the ats_optimization_plan and weave in the missing_keywords naturally where "
        "truthful. Produce: a sharp executive_summary, a list of rewritten "
        "professional_experience bullet points (quantified, action-led), a curated "
        "skills_section, and a complete full_markdown resume ready to export. Then give an "
        "honest ats_after score (0-100) reflecting the optimized resume.\n\n"
        "DO NOT fabricate experience, employers, dates, or metrics that are not supported "
        "by the original resume.\n\n"
        f"OPTIMIZATION_CONTEXT:\n{json.dumps(context)}\n\n"
        f"ORIGINAL_RESUME:\n{resume}"
    )
    try:
        log.info("resume_export: stage started")
        gateway = get_workflow_ai_gateway()
        result = gateway.generate_structured_response(prompt, SCHEMA, system=SYSTEM, stage="resume_export")
        log.info("resume_export: stage completed successfully")
        return {
            "optimized_resume": result.get("optimized_resume", {}),
            "ats_before": ats_before,
            "ats_after": int(result.get("ats_after", ats_before)),
        }
    except Exception as e:  # noqa: BLE001
        log.error("resume_export: stage failed", extra={"error": str(e)}, exc_info=True)
        return {
            "errors": [f"resume_export: {e}"],
            "optimized_resume": {},
            "ats_before": ats_before,
            "ats_after": ats_before,
        }
