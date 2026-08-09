import { defineConfig, configDefaults } from 'vitest/config';
import react from '@vitejs/plugin-react';
import path from 'node:path';

export default defineConfig({
  plugins: [react()],
  resolve: { alias: { '@': path.resolve(__dirname, 'src') } },
  // Dev server only — Vercel serves the prebuilt static `dist/` output, this
  // block has no effect in production.
  server: { host: true, port: 5173 },
  test: {
    // happy-dom, not the default 'node' — Guided Apply (Phase 4) added the first component-level
    // tests in this repo, which need a real DOM. authError.test.ts (pure logic, no DOM) is
    // unaffected by this switch.
    environment: 'happy-dom',
    setupFiles: ['./src/test/setup.ts'],
    // `e2e/**` is Playwright Test's own suite (real Chromium, `frontend/playwright.config.ts`) —
    // vitest's default include glob (`**/*.spec.ts`) would otherwise also try to collect it,
    // which fails immediately since Playwright's `test.beforeEach` isn't a vitest API.
    exclude: [...configDefaults.exclude, 'e2e/**'],
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    target: 'es2020',
    rollupOptions: {
      output: {
        manualChunks: {
          'vendor-react': ['react', 'react-dom', 'react-router-dom'],
          'vendor-charts': ['recharts'],
          'vendor-motion': ['framer-motion'],
          'vendor-query': ['@tanstack/react-query', 'axios', 'zustand'],
        },
      },
    },
  },
});
