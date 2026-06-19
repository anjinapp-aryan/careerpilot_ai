import { useId, useState } from 'react';
import { AlertTriangle, CheckCircle2, Rocket } from 'lucide-react';
import { ResumeSelect } from '@/components/workflow/ResumeSelect';
import { JobMultiSelect } from '@/components/workflow/JobMultiSelect';
import { useStartWorkflow, toErrorMessage } from '@/hooks/useStartWorkflow';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/input';
import type { StartWorkflowPayload, WorkflowFormState, WorkflowRun } from '@/types/workflow';

interface WorkflowFormProps {
  /** Optional initial state (e.g. for re-running an existing config). */
  initialValue?: Partial<WorkflowFormState>;
  /**
   * Extra fields merged into the POST body alongside { resumeId, jobIds }.
   * Lets callers satisfy backend-required fields (targetRole, etc.) without
   * this component knowing about them.
   */
  extraPayload?: Record<string, unknown>;
  /** Called after a run is successfully started. */
  onStarted?: (run: WorkflowRun) => void;
}

const EMPTY: WorkflowFormState = { resumeId: '', jobIds: [] };

/**
 * The workflow start form. Owns `{ resumeId, jobIds }` state, composes the
 * resume + job selectors, validates, and submits via the start-workflow
 * mutation. Loading / error / success feedback is handled inline.
 */
export function WorkflowForm({ initialValue, extraPayload, onStarted }: WorkflowFormProps) {
  const [form, setForm] = useState<WorkflowFormState>({ ...EMPTY, ...initialValue });
  const startWorkflow = useStartWorkflow();

  const resumeFieldId = useId();
  const jobFieldId = useId();

  const canSubmit = form.resumeId !== '' && form.jobIds.length > 0 && !startWorkflow.isPending;

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;

    const payload: StartWorkflowPayload = {
      resumeId: form.resumeId,
      jobIds: form.jobIds,
      ...extraPayload,
    };

    startWorkflow.mutate(payload, {
      onSuccess: (run) => {
        setForm({ ...EMPTY });
        onStarted?.(run);
      },
    });
  }

  return (
    <form onSubmit={handleSubmit} noValidate className="space-y-5">
      <div className="grid gap-5 sm:grid-cols-2">
        <div>
          <Label htmlFor={resumeFieldId}>Resume</Label>
          <ResumeSelect
            id={resumeFieldId}
            value={form.resumeId}
            onChange={(resumeId) => setForm((f) => ({ ...f, resumeId }))}
            disabled={startWorkflow.isPending}
          />
        </div>

        <div>
          <Label htmlFor={jobFieldId}>Target jobs</Label>
          <JobMultiSelect
            id={jobFieldId}
            value={form.jobIds}
            onChange={(jobIds) => setForm((f) => ({ ...f, jobIds }))}
            disabled={startWorkflow.isPending}
          />
          <p className="mt-1.5 text-xs text-muted-foreground">
            {form.jobIds.length === 0
              ? 'Select at least one job.'
              : `${form.jobIds.length} job${form.jobIds.length === 1 ? '' : 's'} selected.`}
          </p>
        </div>
      </div>

      {startWorkflow.isError && (
        <div
          role="alert"
          className="flex items-center gap-2 rounded-lg border border-danger/30 bg-danger/10 px-3 py-2 text-sm text-danger"
        >
          <AlertTriangle className="h-4 w-4 shrink-0" />
          {toErrorMessage(startWorkflow.error, 'Failed to start workflow')}
        </div>
      )}

      {startWorkflow.isSuccess && (
        <div
          role="status"
          className="flex items-center gap-2 rounded-lg border border-success/30 bg-success/10 px-3 py-2 text-sm text-success"
        >
          <CheckCircle2 className="h-4 w-4 shrink-0" />
          Workflow started.
        </div>
      )}

      <div className="flex justify-end">
        <Button type="submit" disabled={!canSubmit} loading={startWorkflow.isPending} size="lg">
          {!startWorkflow.isPending && <Rocket className="h-4 w-4" />}
          {startWorkflow.isPending ? 'Starting…' : 'Start workflow'}
        </Button>
      </div>
    </form>
  );
}

export default WorkflowForm;
