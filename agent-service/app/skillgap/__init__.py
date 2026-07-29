"""Skill Gap Intelligence Workflow — the first business workflow on the LangGraph Workflow Runtime.

A standalone, additive LangGraph graph (own state, own agents, own FastAPI route) that never
modifies the existing 8-node career graph (`app/graph.py`) or its `/runs` endpoint. See
`app/skillgap/graph.py` for the node sequence and `app/skillgap/router.py` for the HTTP contract.
"""
