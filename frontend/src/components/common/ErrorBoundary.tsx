import { Component, type ErrorInfo, type ReactNode } from 'react';

interface ErrorBoundaryProps {
  /** Content to guard. */
  children: ReactNode;
  /** Optional custom fallback; receives the error and a reset callback. */
  fallback?: (error: Error, reset: () => void) => ReactNode;
  /** Optional hook for logging to an error reporter. */
  onError?: (error: Error, info: ErrorInfo) => void;
}

interface ErrorBoundaryState {
  error: Error | null;
}

/**
 * Catches render-time errors in its subtree and shows a recoverable fallback
 * instead of unmounting the whole app. Wrap any self-contained surface
 * (e.g. the workflow form) so a thrown error stays contained.
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { error: null };

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    this.props.onError?.(error, info);
    // eslint-disable-next-line no-console
    console.error('ErrorBoundary caught:', error, info);
  }

  reset = (): void => this.setState({ error: null });

  render(): ReactNode {
    const { error } = this.state;
    if (!error) return this.props.children;

    if (this.props.fallback) return this.props.fallback(error, this.reset);

    return (
      <div
        role="alert"
        className="rounded-xl border border-danger/30 bg-danger/10 p-4 text-sm text-danger"
      >
        <p className="font-medium">Something went wrong rendering this section.</p>
        <p className="mt-1 text-danger/80">{error.message}</p>
        <button
          type="button"
          onClick={this.reset}
          className="mt-3 rounded-md bg-danger/15 px-3 py-1.5 text-xs font-medium text-danger hover:bg-danger/25 focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          Try again
        </button>
      </div>
    );
  }
}
