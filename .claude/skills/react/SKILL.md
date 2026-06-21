# React Frontend Skill

## Purpose
Run, build, type-check, and debug a React + TypeScript frontend (Vite or similar). Generic to
any React project — exact ports, env var names, and API base URLs belong in that repo's own
docs, not here.

---

## Workflows

### Workflow: Start the Dev Server

```bash
npm install   # first time, or after dependency changes
npm run dev
```

**Verify**: open the printed local URL in a browser; check the DevTools console (F12) is free
of errors.

**Prerequisites**: Node 18+, npm 9+ (`node -v`, `npm -v`); any backend API the app calls must
be reachable.

---

### Workflow: Build for Production

```bash
npm run build          # outputs an optimized bundle (commonly dist/ or build/)
npm run preview         # serve the production build locally, if the toolchain supports it
```

---

### Workflow: Type-Check

```bash
npx tsc --noEmit
npx tsc --noEmit --pretty false | head -20    # plain output for grepping
```

---

### Workflow: Format / Lint

```bash
npx prettier --check .
npx prettier --write .
npm run lint            # only if the project has a lint script configured
```

---

### Workflow: Debug in the Browser

1. Open DevTools (F12) → Sources tab → set breakpoints under the source tree.
2. Interact with the UI to hit the breakpoint, then step through.

**VS Code**: install a JS/TS debugger extension, launch Chrome/Edge with
`--remote-debugging-port=9222`, then attach via a `launch.json` entry with
`"request": "attach"` and matching `pathMapping`.

---

### Workflow: Inspect / Audit Dependencies

```bash
npm list
npm outdated
npm audit
npm audit fix
```

---

### Workflow: Clean Reinstall

```bash
rm -rf node_modules package-lock.json
npm install
npm run build
```

---

## Checklists

### ✅ Pre-Dev

- [ ] Node 18+, npm 9+
- [ ] Backend (if any) reachable and responding
- [ ] Local env file present with required `VITE_*`/`REACT_APP_*` vars set
- [ ] Dev server port free
- [ ] `node_modules` actually installed (not just `package-lock.json`)

### ✅ Post-Start Verification

- [ ] Dev server reports a local URL with no startup errors
- [ ] Page renders (no blank/white screen)
- [ ] Console is clean of errors
- [ ] Network tab shows successful (2xx) API calls, if the app calls an API

### ✅ Before Committing

- [ ] `npx tsc --noEmit` passes
- [ ] `npm run build` completes without errors
- [ ] Lint passes, if configured
- [ ] No `console.log` left in changed files
- [ ] No unused imports
- [ ] API calls go through the project's shared HTTP client, not ad-hoc `fetch()`/hardcoded URLs

### ✅ Before Opening a PR

- [ ] Production build verified (`npm run build` + preview, if available)
- [ ] Type-checking passes
- [ ] Manually exercised in a browser, including the edge cases the change affects
- [ ] Responsive at common breakpoints if the change touches layout

---

## Troubleshooting

### ❌ Port Already in Use

`EADDRINUSE` → find/kill the process on that port, or pass an alternate port flag to the dev
server command.

### ❌ API Calls Failing (CORS / 401 / Network)

1. Confirm the backend is actually reachable (curl/ping it directly).
2. Check the Network tab: actual request URL, status code, response body.
3. CORS errors mean the *backend's* CORS config needs the frontend's origin allowed — this
   isn't fixable from the frontend.
4. 401s usually mean a missing/expired auth token — check where the app stores it (commonly
   localStorage or a state store) and whether it's actually being attached to requests.

### ❌ TypeScript Errors

```bash
npx tsc --noEmit --pretty false <path/to/file>
```
Common causes: missing `@types/*` package, an unhandled `null`/`undefined`, importing the
wrong type from a module with multiple exports of similar names.

### ❌ `npm install` Fails (ERESOLVE / Peer Conflicts)

```bash
rm package-lock.json
npm install
# or, if a transitive peer-dep conflict is unavoidable right now:
npm install --legacy-peer-deps
```

### ❌ Dev Server Slow / Not Hot-Reloading

Restart it first (`Ctrl+C`, then re-run). If still slow, check `node_modules` size and do a
clean reinstall — a corrupted or huge dependency tree is the most common cause.

### ❌ Styles Not Loading / Page Looks Broken

Restart the dev server, confirm the CSS import paths in the affected components are correct,
and rebuild to rule out a stale dev-server cache.

### ❌ "Cannot Find Module" on a Local Import

Verify the file actually exists at that path, and that the import omits the file extension for
TS/TSX files (`./components/Foo`, not `./components/Foo.tsx`).

### ❌ Bundle Size Warning

Use a bundle-analyzer plugin for the build tool in use (e.g. `rollup-plugin-visualizer` for
Vite/Rollup) to see what's actually large before trying to optimize blindly.

---

## Tips & Best Practices

1. Hot reload means changes should appear without a manual refresh — if they don't, suspect a
   stale dev server, not your code.
2. Run the type-checker before every commit; TypeScript errors caught early are far cheaper
   than ones caught in review.
3. Route all HTTP calls through one shared client (interceptors for auth/error handling belong
   there, not scattered across components).
4. Lazy-load route-level components for large apps; profile with DevTools before optimizing.
5. Test keyboard navigation and basic accessibility on anything interactive.
