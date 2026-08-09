import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright Test config — Guided Apply E2E coverage only (Phase 3 of the Playwright rollout;
 * see `.mcp.json` for the separate, dev-tooling-only Playwright MCP server, and CLAUDE.md's
 * browser-automation sections for the separate, production Playwright Java stack). All three are
 * deliberately independent: this config drives a real Chromium against the Vite dev server with
 * every backend call intercepted via `page.route(...)` — it never touches a real employer site,
 * never touches the real backend/DB, and never reuses MCP's browser process.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : [['list'], ['html', { open: 'never' }]],
  timeout: 30_000,
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
});
