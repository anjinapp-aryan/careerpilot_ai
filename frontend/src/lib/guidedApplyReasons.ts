/**
 * P7 Action 7 — shared copy for `GuidedApplyReason` (backend enum, `domain/GuidedApplyReason.java`).
 * Extracted out of `GuidedApplyBriefPanel.tsx` so `ExecutionEvidencePanel.tsx` and
 * `MySubmissionsPanel.tsx` render the exact same label/explanation for the same reason rather than
 * drifting copies.
 */
export const BLOCKER_LABEL: Record<string, string> = {
  CAPTCHA: 'CAPTCHA / bot-protection challenge',
  BOT_PROTECTION: "Employer's bot protection",
  LOGIN_REQUIRED: 'Employer requires an account login',
  UNSUPPORTED_CONTROL: "A form control this employer's site uses isn't supported yet",
  EMPLOYER_RESTRICTION: 'This employer restricts automated applications',
  AUTOMATION_BLOCKED: "This employer's application system can't be safely automated",
  MANUAL_REQUIRED: 'Manual completion required',
  UNKNOWN_BLOCKER: 'Automation could not continue',
};

/** One honest sentence per reason — CareerPilot never bypasses, never fabricates, never auto-submits. */
export const BLOCKER_EXPLANATION: Record<string, string> = {
  CAPTCHA: 'Automation stopped because a CAPTCHA/bot-protection challenge was detected. CareerPilot did not bypass it or submit the application.',
  BOT_PROTECTION: "Automation stopped because the employer's bot protection blocked it. CareerPilot did not attempt to bypass it.",
  LOGIN_REQUIRED: 'Automation stopped because the employer requires login. CareerPilot never stores or enters credentials — complete the application manually.',
  UNSUPPORTED_CONTROL: 'Automation stopped because this application contains a control CareerPilot could not safely automate.',
  EMPLOYER_RESTRICTION: 'Automation stopped because this employer restricts automated applications.',
  AUTOMATION_BLOCKED: "Automation stopped because this employer's application system can't be safely automated.",
  MANUAL_REQUIRED: 'Automated submission is unavailable for this application. Your application package and recommended answers are ready for manual submission.',
  UNKNOWN_BLOCKER: 'Automation could not continue for this application.',
};

export function blockerLabel(reason: string | null | undefined): string {
  return BLOCKER_LABEL[reason ?? ''] ?? 'Automation could not continue';
}

export function blockerExplanation(reason: string | null | undefined): string {
  return BLOCKER_EXPLANATION[reason ?? ''] ?? BLOCKER_EXPLANATION.UNKNOWN_BLOCKER;
}
