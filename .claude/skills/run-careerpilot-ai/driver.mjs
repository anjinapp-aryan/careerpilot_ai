#!/usr/bin/env node
/**
 * CareerPilot AI smoke driver.
 *
 * Probes each component of the stack and runs a representative end-to-end
 * flow against whatever is actually up. Designed to be useful both when the
 * full stack is running via docker-compose and when only individual services
 * are running standalone (Vite dev server / uvicorn / spring-boot:run).
 *
 * Exit codes:
 *   0  every requested target answered as expected
 *   1  at least one requested target failed (see report)
 *   2  bad arguments
 *
 * Usage:
 *   node driver.mjs                       # auto-detect all defaults
 *   node driver.mjs --backend-only        # skip frontend + agent
 *   node driver.mjs --backend http://localhost:8080 --agent http://localhost:8088
 *   node driver.mjs --no-frontend         # skip frontend
 *   node driver.mjs --e2e                 # also run register→login→dashboard
 *
 * Run from inside the skill dir, or from the repo root, or anywhere — paths
 * are absolute over HTTP. No filesystem deps.
 */

import { argv, exit, env } from 'node:process';

const args = argv.slice(2);
const flag = (n) => args.includes(n);
const opt = (n, d) => {
  const i = args.indexOf(n);
  return i >= 0 && i + 1 < args.length ? args[i + 1] : d;
};

const BACKEND = opt('--backend', env.CAREERPILOT_BACKEND || 'http://localhost:8080');
const AGENT = opt('--agent', env.CAREERPILOT_AGENT || 'http://localhost:8088');
const FRONTEND = opt('--frontend', env.CAREERPILOT_FRONTEND || 'http://localhost:5173');
const RUN_FRONTEND = !flag('--no-frontend') && !flag('--backend-only') && !flag('--agent-only');
const RUN_BACKEND = !flag('--no-backend') && !flag('--agent-only');
const RUN_AGENT = !flag('--no-agent') && !flag('--backend-only');
const RUN_E2E = flag('--e2e');

const TIMEOUT_MS = Number(opt('--timeout-ms', '4000'));

const results = [];
const ok = (name, detail) => { results.push({ name, ok: true, detail }); console.log(`  ✓ ${name}${detail ? ` — ${detail}` : ''}`); };
const fail = (name, detail) => { results.push({ name, ok: false, detail }); console.log(`  ✗ ${name} — ${detail}`); };
const section = (s) => console.log(`\n[${s}]`);

async function fetchT(url, opts = {}) {
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), TIMEOUT_MS);
  try {
    return await fetch(url, { ...opts, signal: ctl.signal });
  } finally {
    clearTimeout(t);
  }
}

async function probeFrontend() {
  section(`frontend @ ${FRONTEND}`);
  try {
    const r = await fetchT(FRONTEND + '/');
    if (!r.ok) return fail('GET /', `HTTP ${r.status}`);
    const html = await r.text();
    if (!html.includes('CareerPilot')) return fail('GET /', 'response body missing "CareerPilot"');
    ok('GET /', 'serves index with brand string');
    // Vite dev-server signature
    if (html.includes('/@vite/client')) ok('vite dev mode', '/@vite/client present');
    else ok('mode', 'production-built bundle');
  } catch (e) {
    fail('GET /', e.message);
  }
}

async function probeAgent() {
  section(`agent-service @ ${AGENT}`);
  try {
    const r = await fetchT(AGENT + '/health');
    if (!r.ok) return fail('GET /health', `HTTP ${r.status}`);
    const j = await r.json();
    if (j.status !== 'ok') return fail('GET /health', `status=${j.status}`);
    ok('GET /health', `provider=${j.provider} model=${j.model}`);
  } catch (e) {
    return fail('GET /health', e.message);
  }
  try {
    const r = await fetchT(AGENT + '/openapi.json');
    if (!r.ok) return fail('GET /openapi.json', `HTTP ${r.status}`);
    const spec = await r.json();
    const paths = Object.keys(spec.paths || {});
    const expected = ['/health', '/runs', '/runs/resume', '/runs/{thread_id}'];
    const missing = expected.filter((p) => !paths.includes(p));
    if (missing.length) fail('openapi paths', `missing ${missing.join(',')}`);
    else ok('openapi paths', `4/4 routes present`);
  } catch (e) {
    fail('GET /openapi.json', e.message);
  }
}

async function probeBackend() {
  section(`backend @ ${BACKEND}`);
  try {
    const r = await fetchT(BACKEND + '/actuator/health');
    if (!r.ok) return fail('GET /actuator/health', `HTTP ${r.status}`);
    const j = await r.json();
    if (j.status !== 'UP') return fail('GET /actuator/health', `status=${j.status}`);
    ok('GET /actuator/health', 'UP');
  } catch (e) {
    return fail('GET /actuator/health', e.message);
  }
  try {
    const r = await fetchT(BACKEND + '/v3/api-docs');
    if (!r.ok) return fail('GET /v3/api-docs', `HTTP ${r.status}`);
    const spec = await r.json();
    const paths = Object.keys(spec.paths || {});
    const wanted = ['/api/auth/login', '/api/auth/register', '/api/dashboard', '/api/workflows/run'];
    const missing = wanted.filter((p) => !paths.includes(p));
    if (missing.length) fail('openapi paths', `missing ${missing.join(',')}`);
    else ok('openapi paths', `${wanted.length}/${wanted.length} core routes present`);
  } catch (e) {
    fail('GET /v3/api-docs', e.message);
  }
}

async function e2eRegisterLoginDashboard() {
  section('e2e: register → login → dashboard');
  const stamp = Date.now();
  const email = `smoke+${stamp}@careerpilot.local`;
  const password = 'SmokeTest!Password1';
  let token;
  try {
    const r = await fetchT(BACKEND + '/api/auth/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        organizationName: `Smoke Org ${stamp}`,
        email, password, fullName: 'Smoke Test',
      }),
    });
    if (!r.ok) {
      const body = await r.text();
      return fail('POST /api/auth/register', `HTTP ${r.status}: ${body.slice(0, 200)}`);
    }
    const j = await r.json();
    token = j.accessToken;
    if (!token) return fail('POST /api/auth/register', 'missing accessToken in response');
    ok('POST /api/auth/register', `user=${j.userId.slice(0, 8)} org=${j.orgId.slice(0, 8)}`);
  } catch (e) {
    return fail('POST /api/auth/register', e.message);
  }

  try {
    const r = await fetchT(BACKEND + '/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    });
    if (!r.ok) return fail('POST /api/auth/login', `HTTP ${r.status}`);
    const j = await r.json();
    if (!j.accessToken) return fail('POST /api/auth/login', 'missing accessToken');
    token = j.accessToken;
    ok('POST /api/auth/login', 'token issued');
  } catch (e) {
    return fail('POST /api/auth/login', e.message);
  }

  try {
    const r = await fetchT(BACKEND + '/api/dashboard', {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!r.ok) return fail('GET /api/dashboard', `HTTP ${r.status}`);
    const j = await r.json();
    const keys = ['careerHealthScore', 'resumeScore', 'atsScore', 'jobMatchScore'];
    const missing = keys.filter((k) => !(k in j));
    if (missing.length) return fail('GET /api/dashboard', `missing keys: ${missing.join(',')}`);
    ok('GET /api/dashboard', `health=${j.careerHealthScore} resume=${j.resumeScore}`);
  } catch (e) {
    return fail('GET /api/dashboard', e.message);
  }

  try {
    const r = await fetchT(BACKEND + '/api/jobs', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({
        title: 'Smoke Senior Engineer',
        company: 'SmokeCo',
        location: 'Remote',
        description: 'Drive distributed systems; mentor seniors; ship product.',
      }),
    });
    if (!r.ok) return fail('POST /api/jobs', `HTTP ${r.status}`);
    const j = await r.json();
    if (!j.id) return fail('POST /api/jobs', 'missing id');
    ok('POST /api/jobs', `job=${j.id.slice(0, 8)}`);
  } catch (e) {
    fail('POST /api/jobs', e.message);
  }
}

(async () => {
  console.log('CareerPilot AI — smoke driver');
  console.log(`  backend=${BACKEND}  agent=${AGENT}  frontend=${FRONTEND}`);
  console.log(`  timeout=${TIMEOUT_MS}ms  e2e=${RUN_E2E}`);

  if (RUN_FRONTEND) await probeFrontend();
  if (RUN_AGENT) await probeAgent();
  if (RUN_BACKEND) await probeBackend();
  if (RUN_E2E && RUN_BACKEND) await e2eRegisterLoginDashboard();

  const failed = results.filter((r) => !r.ok);
  console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
  if (failed.length) {
    console.log('FAIL:');
    for (const f of failed) console.log(`  - ${f.name}: ${f.detail}`);
    exit(1);
  }
  console.log('OK');
})().catch((e) => { console.error(e); exit(2); });
