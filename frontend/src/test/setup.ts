import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';

// vitest.config's `test.globals` is intentionally left off (repo-wide convention — see
// authError.test.ts's explicit vitest imports), so @testing-library/react's automatic
// afterEach-cleanup detection doesn't fire. Without this, an unmounted component's DOM survives
// into the next test, producing false "multiple elements found" failures.
afterEach(() => cleanup());
