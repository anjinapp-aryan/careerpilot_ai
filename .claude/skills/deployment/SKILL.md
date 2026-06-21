# Deployment Skill

## Purpose
Build Docker images for production, deploy full stack, verify deployment, run end-to-end tests.

---

## Workflows

### Workflow: Build All Service Images

```bash
# Build all images with latest code
docker compose build --no-cache

# Expected output: Shows stages for backend, agent-service, frontend
# backend stage 1: Maven build
# agent-service: Python dependencies
# frontend: npm build
# Final layer: runtime images

# Verify images created
docker images | grep careerpilot
# Should show 3 images: backend, agent-service, frontend with LATEST tag
```

**Troubleshooting build failures**:
```bash
# If Maven fails in backend build, check logs
docker compose build --no-cache backend 2>&1 | tail -50 | grep -i "error"

# If npm fails in frontend build
docker compose build --no-cache frontend 2>&1 | tail -50 | grep -i "error"

# If Python fails in agent-service
docker compose build --no-cache agent-service 2>&1 | tail -50 | grep -i "error"
```

---

### Workflow: Build Specific Service

```bash
# Rebuild just backend (after Java changes)
docker compose build --no-cache backend

# Rebuild just frontend (after React changes)
docker compose build --no-cache frontend

# Rebuild just agent-service (after Python changes)
docker compose build --no-cache agent-service
```

---

### Workflow: Deploy Full Stack (Development)

```bash
# Set environment variables
export ENVIRONMENT=dev

# Ensure .env is configured
cat .env | grep -E "JWT_SECRET|GEMINI_API_KEY|DATABASE_URL" | head -3

# Build images
docker compose build --no-cache

# Start stack
docker compose --env-file .env up -d

# Watch startup (Ctrl+C to exit)
docker compose logs -f

# Wait for services ready (~30 seconds)
sleep 30

# Verify health
docker compose ps
# All should show RUNNING
```

---

### Workflow: Deploy Full Stack (Production-Like)

```bash
# Use production .env file
export ENVIRONMENT=prod

# Build images
docker compose build --no-cache

# Start with production tag
docker compose --env-file .env.prod -f docker-compose.yml up -d

# Verify all containers running
docker compose --env-file .env.prod ps

# Check resource usage (should be reasonable)
docker stats --no-stream | grep -E "careerpilot|CONTAINER"
```

---

### Workflow: Run End-to-End Tests

```bash
# Verify full stack working before deployment
# See .claude/skills/run-careerpilot-ai/driver.mjs for test harness

node .claude/skills/run-careerpilot-ai/driver.mjs --e2e

# Expected output:
# ✓ Health check: backend returns 200
# ✓ Health check: agent-service returns 200
# ✓ Auth: register new user
# ✓ Auth: login returns JWT token
# ✓ Workflows: create resume
# ✓ Workflows: create job
# ✓ Workflows: start workflow
# ✓ Workflows: workflow completes or waits for approval

# Exit code 0 = all tests passed
echo $?
# Should output: 0
```

---

### Workflow: Verify Deployment Health

```bash
# Check all endpoints returning 200 OK

# Backend diagnostics
curl -s http://localhost:8080/api/diagnostics/ai | jq '.gateway_health'

# Backend health
curl -s http://localhost:8080/api/health | jq .

# Agent service health
curl -s http://localhost:8088/health | jq .

# Frontend health
curl -s http://localhost:5173 | head -c 100
# Should start with <!DOCTYPE or <html

# Check no critical errors
docker compose logs | grep -i "error\|exception" | wc -l
# Should be < 5
```

---

### Workflow: Update Service in Running Stack

```bash
# After code changes, redeploy without downtime

# 1. Build updated image
docker compose build --no-cache backend

# 2. Deploy just that service
docker compose up -d backend

# 3. Verify deployment
docker compose logs -f backend
# Wait for "Started CareerPilotBackendApplication"

# 4. Run health check
curl http://localhost:8080/api/diagnostics/ai

# 5. Run tests
node .claude/skills/run-careerpilot-ai/driver.mjs --e2e
```

---

### Workflow: Rollback to Previous Version

```bash
# If deployment fails, revert

# 1. Get previous commit
git log --oneline -5

# 2. Checkout previous version
git checkout <previous-commit-hash>

# 3. Rebuild images from previous code
docker compose build --no-cache

# 4. Restart stack
docker compose down
docker compose --env-file .env up -d

# 5. Verify
docker compose ps
curl http://localhost:8080/api/diagnostics/ai
```

---

### Workflow: Push Images to Registry

```bash
# Tag images for registry
docker tag careerpilot-backend:latest myregistry.azurecr.io/careerpilot-backend:latest
docker tag careerpilot-agent-service:latest myregistry.azurecr.io/careerpilot-agent-service:latest
docker tag careerpilot-frontend:latest myregistry.azurecr.io/careerpilot-frontend:latest

# Login to registry
docker login myregistry.azurecr.io
# Or: az acr login --name myregistry

# Push images
docker push myregistry.azurecr.io/careerpilot-backend:latest
docker push myregistry.azurecr.io/careerpilot-agent-service:latest
docker push myregistry.azurecr.io/careerpilot-frontend:latest

# Verify pushed
docker image ls | grep myregistry
```

---

### Workflow: Deploy to Cloud (Kubernetes Example)

```bash
# Update docker-compose.yml references to registry images
# Or use Kubernetes manifests with image registry URLs

# Example kubectl commands (if using k8s)
kubectl apply -f k8s/backend.yaml
kubectl apply -f k8s/agent-service.yaml
kubectl apply -f k8s/frontend.yaml

# Monitor deployment
kubectl get pods
kubectl logs <pod-name> -f

# Port forward to test
kubectl port-forward service/backend 8080:8080
curl http://localhost:8080/api/diagnostics/ai
```

---

## Checklists

### ✅ Pre-Build Checklist

- [ ] Code committed: `git status` shows clean
- [ ] Branch correct: `git branch` shows intended branch
- [ ] Tests passing (if applicable): `mvn test` or `npm test`
- [ ] No uncommitted changes: `git diff --quiet` returns 0
- [ ] Version bumped (optional): Check version.txt or package.json
- [ ] Docker daemon running: `docker ps` succeeds
- [ ] Disk space available: `docker system df` shows space available
- [ ] .env file complete: All required vars set and non-empty

### ✅ Build Phase

- [ ] Backend builds: `docker compose build --no-cache backend` succeeds
- [ ] Agent-service builds: `docker compose build --no-cache agent-service` succeeds
- [ ] Frontend builds: `docker compose build --no-cache frontend` succeeds
- [ ] No warnings in build output: Check for yellow/orange text
- [ ] Images created: `docker images | grep careerpilot | wc -l` = 3

### ✅ Deployment Phase

- [ ] Stack starts: `docker compose up -d` exits cleanly
- [ ] All containers running: `docker compose ps` shows all RUNNING
- [ ] No startup errors: `docker compose logs` shows clean startup
- [ ] Services responsive:
  - [ ] Backend: `curl http://localhost:8080/api/diagnostics/ai` returns 200
  - [ ] Agent: `curl http://localhost:8088/health` returns 200
  - [ ] Frontend: `curl http://localhost:5173` returns HTML

### ✅ Verification Phase

- [ ] E2E tests pass: `node .claude/skills/run-careerpilot-ai/driver.mjs --e2e` exits with 0
- [ ] Health checks pass: All diagnostics endpoints return 200
- [ ] No critical logs: `docker compose logs | grep -i "error\|exception" | wc -l` < 5
- [ ] Resource usage reasonable:
  - [ ] CPU < 50%: `docker stats --no-stream | grep -v CONTAINER | awk '{print $3}'`
  - [ ] Memory < 2GB per service: `docker stats --no-stream`

### ✅ Post-Deployment

- [ ] Tag images (if releasing): `docker tag careerpilot-backend:latest careerpilot-backend:v0.2.0`
- [ ] Document deployment: Add entry to DEPLOYMENT_LOG.md
- [ ] Monitor health: Check dashboards/logs for next 1 hour
- [ ] Ready to scale: No errors preventing horizontal scaling

---

## Troubleshooting

### ❌ Issue: Maven Build Fails in Backend Docker Build

**Error**: `[ERROR] Failed to execute goal`

**Debug**:
```bash
# Get full error output
docker compose build --no-cache backend 2>&1 > build.log
tail -100 build.log | grep -A 5 "ERROR"

# Common issues:
# 1. Missing dependency → Check pom.xml
# 2. Java version mismatch → Check Dockerfile
# 3. Test failures → Use -DskipTests in Dockerfile
```

**Fix**:
```bash
# In backend/Dockerfile, ensure:
# FROM maven:3.9-eclipse-temurin-25 (or appropriate base image)
# RUN mvn -DskipTests clean package (skips tests if they fail)

# Rebuild
docker compose build --no-cache backend
```

---

### ❌ Issue: npm Build Fails in Frontend Docker Build

**Error**: `npm ERR! code ERESOLVE`

**Fix**:
```bash
# In frontend/Dockerfile, use legacy peer deps
RUN npm install --legacy-peer-deps
RUN npm run build

# Rebuild
docker compose build --no-cache frontend
```

---

### ❌ Issue: Python Dependencies Fail in Agent Service

**Error**: `pip: command not found` or dependency install fails

**Debug**:
```bash
# Check Dockerfile uses correct Python
# Should have: FROM python:3.10 or python:3.11

docker compose build --no-cache agent-service 2>&1 | grep -i "error"

# May need to add system packages
# In Dockerfile: RUN apt-get update && apt-get install -y <packages>
```

---

### ❌ Issue: Container Starts but Application Fails to Start

**Status**: Container shows RUNNING but logs show errors

**Debug**:
```bash
docker compose logs <service> | tail -50

# Common causes:
# 1. Missing env var → Check docker-compose.yml environment section
# 2. Port conflict → Ensure port not used: lsof -i :8080
# 3. Database not ready → Increase startup delay, add healthchecks
# 4. Config file missing → Verify all files copied in Dockerfile
```

---

### ❌ Issue: E2E Tests Fail

**Error**: Test driver reports failures

**Debug**:
```bash
# Run with verbose output
node .claude/skills/run-careerpilot-ai/driver.mjs --e2e --verbose

# Shows which step failed, can manually test that endpoint
curl -X POST http://localhost:8080/api/auth/register -H "Content-Type: application/json" -d '{...}'

# Check backend logs at that point
docker compose logs backend | grep -i "error"

# May need to rebuild:
docker compose down -v
docker compose up -d
sleep 30  # Wait for database initialization
node .claude/skills/run-careerpilot-ai/driver.mjs --e2e
```

---

### ❌ Issue: Deployment Slow / Services Not Responsive After Deploy

**Symptoms**: Stack deployed but calls timeout or fail

**Causes**:
1. Database still initializing
2. Migrations running (Flyway)
3. Services waiting for dependencies

**Fix**:
```bash
# Add more startup time
sleep 60

# Then verify
curl http://localhost:8080/api/diagnostics/ai

# Check logs
docker compose logs | grep -i "migration\|database\|error"

# Database may take 1-2 minutes if large
# Check connection
docker compose exec db psql -U postgres -c "SELECT version()"
```

---

### ❌ Issue: Out of Disk Space During Build

**Error**: `no space left on device`

**Fix**:
```bash
# Remove old images/containers
docker system prune -a

# Show disk usage
docker system df

# Remove large items
docker image rm <image-id>
docker volume rm <volume-id>

# Check host disk
df -h /

# If host disk full, delete old files outside Docker
rm -rf /tmp/*
```

---

### ❌ Issue: Registry Push Fails (Authentication)

**Error**: `denied: requested access to the resource is denied`

**Fix**:
```bash
# Verify login
docker login myregistry.azurecr.io

# If using Azure, try:
az acr login --name myregistry

# Verify image is tagged correctly
docker images | grep careerpilot

# May need full path in tag
docker tag careerpilot-backend:latest myregistry.azurecr.io/careerpilot-backend:latest

# Then push
docker push myregistry.azurecr.io/careerpilot-backend:latest
```

---

## Tips & Best Practices

1. **Always use `--no-cache`** for production builds to ensure fresh dependencies
2. **Test locally before pushing**: Run full E2E before pushing to registry
3. **Tag releases**: `docker tag careerpilot-backend:latest careerpilot-backend:v1.2.3`
4. **Keep Dockerfiles minimal**: Multi-stage builds reduce image size
5. **Monitor deployment**: Watch logs for 1 hour after deploy for issues
6. **Automated rollback**: Have script ready to revert to previous commit
7. **Database migrations**: Flyway handles automatically, but verify `flyway_schema_history` table
8. **Health checks**: Implement readiness/liveness probes for orchestrators (k8s)

---

**Status**: 🟢 Ready  
**Last Updated**: 2026-06-20
