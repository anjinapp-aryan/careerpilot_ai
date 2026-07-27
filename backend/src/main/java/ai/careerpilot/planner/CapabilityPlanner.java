package ai.careerpilot.planner;

import ai.careerpilot.intent.IntentResult;

/**
 * Phase 11.2 — turns a classified {@link IntentResult} (Phase 11.1) into a {@link
 * CapabilityPlan}. This is the bridge the Phase 11.1 package javadoc promised: the first code in
 * the codebase that maps {@code IntentType} to {@code CapabilityType}. Produces a plan only —
 * executing it is explicitly out of scope (Phase 11.3).
 */
public interface CapabilityPlanner {

    CapabilityPlan plan(IntentResult intentResult);
}
