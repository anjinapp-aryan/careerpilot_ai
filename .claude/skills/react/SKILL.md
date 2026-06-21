# React Frontend Skill

## Purpose
Manage React 18 + Vite + TypeScript frontend: run dev server, build, test, type-check, debug.

---

## Workflows

### Workflow: Start Development Server

```bash
cd frontend

# First time: install dependencies
npm install

# Start dev server
npm run dev
```

**Expected output**:
```
✓ built in Xs
➜  Local:   http://localhost:5173/
➜  press h to show help
```

**Verify running**:
- Open http://localhost:5173 in browser
- Should see login page (if not authenticated)
- No console errors (F12 → Console tab)

**Prerequisites**:
- Node.js 18+ installed: `node -v`
- npm 9+ installed: `npm -v`
- Backend running on http://localhost:8080
- No port 5173 conflicts

---

### Workflow: Build for Production

```bash
cd frontend

# Install dependencies (if needed)
npm install

# Build optimized bundle
npm run build

# Expected output: dist/ folder with optimized JS/CSS
ls -lah dist/

# Test production build locally
npm run preview

# Open http://localhost:4173 to view
```

---

### Workflow: Type-Check TypeScript

```bash
cd frontend

# Check for TypeScript errors (no emit)
npx tsc --noEmit

# Show detailed errors
npx tsc --noEmit --pretty false | head -20

# Check specific file
npx tsc src/lib/api.ts --noEmit
```

---

### Workflow: Format Code (Prettier)

```bash
cd frontend

# Format all files
npx prettier --write .

# Format specific file
npx prettier --write src/components/Dashboard.tsx

# Check formatting without writing
npx prettier --check .
```

---

### Workflow: Debug in Browser

```bash
cd frontend

# Dev server already running (npm run dev)

# In browser
1. Press F12 to open DevTools
2. Go to Sources tab
3. Set breakpoints in source files (src/)
4. Interact with UI to trigger breakpoints
5. Step through code using debugger
```

**VS Code debugging**:
1. Install extension: "Debugger for Chrome"
2. Create `.vscode/launch.json`:
```json
{
  "version": "0.2.0",
  "configurations": [
    {
      "type": "chrome",
      "request": "attach",
      "name": "Attach to Chrome",
      "port": 9222,
      "pathMapping": {
        "/": "${workspaceRoot}",
        "/src": "${workspaceRoot}/src"
      }
    }
  ]
}
```

3. Start Chrome with remote debugging: `google-chrome --remote-debugging-port=9222`
4. Open http://localhost:5173
5. In VS Code: Run → Start Debugging

---

### Workflow: Check Dependencies

```bash
cd frontend

# List installed packages
npm list

# Check for updates
npm outdated

# Security audit
npm audit

# Fix vulnerabilities
npm audit fix
```

---

### Workflow: Clean Build (Full Reset)

```bash
cd frontend

# Remove node_modules and lock file
rm -rf node_modules package-lock.json

# Reinstall
npm install

# Rebuild
npm run build
```

---

### Workflow: Test Specific Component

```bash
cd frontend

# Find component
find src -name "*Dashboard*"

# Type-check it
npx tsc src/pages/Dashboard.tsx --noEmit

# Build and preview to test
npm run build
npm run preview
```

---

## Checklists

### ✅ Pre-Dev Checklist

- [ ] Node.js 18+: `node -v` shows 18.x or higher
- [ ] npm 9+: `npm -v` shows 9.x or higher
- [ ] Backend running: `curl http://localhost:8080/api/diagnostics/ai` returns 200
- [ ] .env.local exists: `ls .env.local`
- [ ] `VITE_API_BASE_URL` set in .env.local: `grep VITE_API_BASE_URL .env.local`
- [ ] Port 5173 free: `lsof -i :5173` returns empty
- [ ] node_modules present: `ls node_modules | wc -l` > 100

### ✅ Post-Server-Start Verification

- [ ] Dev server started: logs show "Local: http://localhost:5173/"
- [ ] Page loads: No white screen, see UI elements
- [ ] No console errors: F12 → Console tab is clean
- [ ] API calls working: Network tab shows 200 responses from /api/ endpoints
- [ ] Styles loaded: Page doesn't look broken/unstyled

### ✅ Before Committing Code

- [ ] TypeScript passes: `npx tsc --noEmit` succeeds
- [ ] Builds successfully: `npm run build` completes without errors
- [ ] No ESLint errors: If using linter: `npm run lint` passes
- [ ] Component tested: Manually tested in browser
- [ ] No unused imports: Check for red squiggles in editor
- [ ] Code formatted: Run `npx prettier --write .`
- [ ] API calls use lib/api.ts: No hardcoded HTTP URLs
- [ ] No console.log() in production code: Search for `console.log`

### ✅ Before Creating PR

- [ ] Production build works: `npm run build && npm run preview`
- [ ] Type checking passes: `npx tsc --noEmit`
- [ ] No dead code
- [ ] Error boundaries in place for new features
- [ ] Responsive design tested (mobile, tablet, desktop)

---

## Troubleshooting

### ❌ Issue: Port 5173 Already in Use

**Error message**: `listen EADDRINUSE: address already in use :::5173`

**Fix**:
```bash
# Find process
lsof -i :5173

# Kill it
kill -9 <PID>

# Or use different port
npm run dev -- --port 3000
```

---

### ❌ Issue: API Calls Failing (CORS, 401, Network)

**Error in browser console**: `Access to XMLHttpRequest blocked by CORS policy`

**Checks**:
1. Backend running: `curl http://localhost:8080/api/diagnostics/ai`
2. Check Network tab in DevTools:
   - What is the actual request URL?
   - What is the response status code?
   - What is the response body?

**Fixes**:
```bash
# If backend not responding
cd backend && mvn spring-boot:run

# If CORS error: Check backend CORS config
grep -r "CrossOrigin\|allowedOrigins" backend/src

# If 401 Unauthorized: Check JWT token
# F12 → Application → Local Storage → auth store
# Should have accessToken field
```

---

### ❌ Issue: TypeScript Compilation Errors

**Error message**: `Type 'X' is not assignable to type 'Y'`

**Fix**:
```bash
# Show full error with context
npx tsc --noEmit --pretty false src/components/MyComponent.tsx

# Common causes:
# 1. Missing type definitions
# 2. Null/undefined not handled
# 3. Wrong type imported

# Check node_modules/@types for missing types
ls node_modules/@types/ | grep -i "react"

# Install missing types
npm install --save-dev @types/node-fetch
```

---

### ❌ Issue: npm install Fails

**Error message**: `npm ERR! code ERESOLVE`, dependency conflicts

**Fix**:
```bash
# Remove lock file and reinstall
rm package-lock.json
npm install

# If still failing, use legacy resolution
npm install --legacy-peer-deps

# Check what's conflicting
npm ls

# Update package.json to resolve
npm update
```

---

### ❌ Issue: Development Server Hangs / Slow

**Symptoms**: Changes not hot-reloading, very slow refresh

**Fix**:
```bash
# Kill and restart
Ctrl+C
npm run dev

# If still slow, check for large node_modules
du -sh node_modules

# Remove and reinstall
rm -rf node_modules package-lock.json
npm install
npm run dev
```

---

### ❌ Issue: CSS Not Loading / Styles Broken

**Symptoms**: Page unstyled, missing colors/layout

**Causes**: Vite dev server issue or CSS import problem

**Fix**:
```bash
# Restart dev server
Ctrl+C
npm run dev

# Check CSS files exist
find src -name "*.css" | head -10

# In component, verify import path
grep -r "import.*\.css" src/ | head -5

# Rebuild
npm run build
npm run preview
```

---

### ❌ Issue: "Cannot find module" Import Error

**Error message**: `Cannot find module './components/Dashboard' or its corresponding type declarations`

**Fix**:
```bash
# Verify file exists
ls src/components/Dashboard.tsx

# Check import statement - file extension
# React components: should NOT have .tsx extension in import
# ❌ import { Dashboard } from './components/Dashboard.tsx'
# ✅ import { Dashboard } from './components/Dashboard'

# Restart dev server if you changed files
npm run dev

# Clear VS Code cache
# Command Palette → "Developer: Reload Window"
```

---

### ❌ Issue: JWT Token Expired (401 Unauthorized)

**Symptoms**: API calls return 401, login doesn't work

**Fix**:
```bash
# Clear stored auth
# F12 → Application → Local Storage → Clear all

# Login again
# Should get new JWT token

# Check token expiry
# If consistently expiring fast, check backend JWT_SECRET configuration
curl http://localhost:8080/api/diagnostics/ai
```

---

### ❌ Issue: Build Output Too Large

**Error message**: Warning about bundle size being > 1MB

**Fix**:
```bash
# Analyze bundle
npm install --save-dev vite-plugin-visualizer

# Add to vite.config.ts
import { visualizer } from "vite-plugin-visualizer";

export default {
  plugins: [visualizer()],
}

# Rebuild and open dist/stats.html to see what's large
npm run build
```

---

## Tips & Best Practices

1. **Hot module reloading (HMR)**: Changes auto-reload in browser, NO manual refresh needed
2. **Type safety**: Always run `npx tsc --noEmit` before committing
3. **API base URL**: Use `lib/api.ts` axios instance, never `fetch()` or hardcoded URLs
4. **State management**: Check `lib/auth.ts` for zustand store patterns
5. **Responsive design**: Test at 320px, 768px, 1920px widths
6. **Accessibility**: Use semantic HTML, test with keyboard navigation
7. **Error handling**: Wrap new components in ErrorBoundary if they fetch data
8. **Performance**: Lazy-load pages with `React.lazy()`, profile with DevTools

---

**Status**: 🟢 Ready  
**Last Updated**: 2026-06-20
