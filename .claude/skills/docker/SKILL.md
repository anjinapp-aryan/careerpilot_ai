# Docker & Services Skill

## Purpose
Manage local Docker services: start/stop containers, view logs, reset data, debug services.

---

## Workflows

### Workflow: Start All Services (First Time)

```bash
# Copy and configure environment
cp .env.example .env

# Edit .env with required values:
# JWT_SECRET=<min 32 random chars>
# GEMINI_API_KEY=<your key>
# DATABASE_URL=postgresql://user:pass@neon.tech/db (DIRECT endpoint, NO -pooler)
# DATABASE_URL_PY=<matching libpq form>
```

**Use text editor to set values**:
```bash
# After editing .env, verify critical values are set:
grep -E "JWT_SECRET|GEMINI_API_KEY|DATABASE_URL" .env | grep -v "^#"
# Should show 3 lines with non-empty values
```

**Start services**:
```bash
docker compose --env-file .env up -d

# Watch startup (Ctrl+C to exit)
docker compose logs -f
```

**Wait for services to be ready** (watch logs for):
- ✅ Zookeeper: "binding to port 2181"
- ✅ Kafka: "started"
- ✅ PostgreSQL: "ready to accept connections"
- ✅ Redis: "Ready to accept connections"
- ✅ MinIO: "MinIO Object Storage Server"

---

### Workflow: Check Service Status

```bash
# Show all containers and their status
docker compose ps

# Should show: postgres, zookeeper, kafka, redis, minio, all RUNNING

# Verify each service responds
docker compose exec db psql -U postgres -c "SELECT 1" | grep "1"     # DB
docker compose exec redis redis-cli PING                              # Redis
docker compose exec kafka kafka-topics --bootstrap-server localhost:9092 --list  # Kafka
curl http://localhost:9001/minio/health                               # MinIO
```

---

### Workflow: View Logs for Service

```bash
# Real-time logs for specific service
docker compose logs -f backend          # Follow backend logs (Ctrl+C to exit)
docker compose logs -f agent-service    # Follow agent service logs
docker compose logs -f db               # Follow database logs

# Last N lines without following
docker compose logs --tail=50 backend

# All logs at once (one-time output)
docker compose logs

# Search logs
docker compose logs backend | grep -i "error"
docker compose logs backend | grep -i "workflow"
```

---

### Workflow: Stop All Services

```bash
# Stop all containers (data preserved)
docker compose down

# Verify stopped
docker compose ps
# Should show empty or "Exited" status
```

---

### Workflow: Restart Single Service

```bash
# Restart backend only
docker compose restart backend

# Restart database
docker compose restart db

# Restart agent-service
docker compose restart agent-service

# Watch logs to verify
docker compose logs -f <service>
```

---

### Workflow: Clean & Reset Everything

```bash
# Stop containers AND delete volumes (destroys all data)
docker compose down -v

# Verify volumes deleted
docker volume ls | grep careerpilot
# Should return empty

# Start fresh
docker compose --env-file .env up -d
```

---

### Workflow: Rebuild Service After Code Changes

```bash
# Rebuild backend (Java changes)
docker compose build --no-cache backend
docker compose up -d backend

# Rebuild agent-service (Python changes)
docker compose build --no-cache agent-service
docker compose up -d agent-service

# Rebuild frontend (Node changes)
docker compose build --no-cache frontend
docker compose up -d frontend

# Or rebuild all
docker compose build --no-cache
docker compose up -d
```

---

### Workflow: Execute Command Inside Container

```bash
# Run psql inside database container
docker compose exec db psql -U postgres -d careerpilot -c "SELECT COUNT(*) FROM users;"

# Connect to Python shell in agent-service
docker compose exec agent-service python3

# Check environment variables in backend
docker compose exec backend env | grep GEMINI

# View files inside container
docker compose exec backend ls /app/config
```

---

### Workflow: View Resource Usage

```bash
# See memory/CPU for each container
docker stats

# Format: Container | CPU % | Memory Usage | Limit | Memory % | Net I/O | Block I/O
```

---

### Workflow: Clean Up Disk Space

```bash
# Remove unused images
docker image prune -a

# Remove unused volumes
docker volume prune

# Remove unused networks
docker network prune

# Full cleanup
docker system prune -a

# See disk usage
docker system df
```

---

## Checklists

### ✅ Pre-Docker Checklist

- [ ] Docker daemon running: `docker ps` succeeds
- [ ] docker-compose installed: `docker compose --version` shows v2+
- [ ] .env file exists and populated: `ls .env && grep "^[A-Z]" .env | wc -l` > 5
- [ ] DATABASE_URL set correctly: `grep DATABASE_URL .env | grep -v pooler`
- [ ] Ports available:
  - [ ] 5432 (PostgreSQL): `lsof -i :5432` returns empty
  - [ ] 6379 (Redis): `lsof -i :6379` returns empty
  - [ ] 9092 (Kafka): `lsof -i :9092` returns empty
  - [ ] 9001 (MinIO): `lsof -i :9001` returns empty
  - [ ] 8080 (Backend): `lsof -i :8080` returns empty
  - [ ] 8088 (Agent Service): `lsof -i :8088` returns empty
  - [ ] 5173 (Frontend): `lsof -i :5173` returns empty

### ✅ Post-Startup Verification

- [ ] All containers running: `docker compose ps` shows all RUNNING
- [ ] PostgreSQL responsive: `docker compose exec db psql -U postgres -c "SELECT 1"`
- [ ] Redis responsive: `docker compose exec redis redis-cli PING` returns PONG
- [ ] Kafka broker ready: Check logs for "started" message
- [ ] MinIO accessible: `curl http://localhost:9001/minio/health` returns 200
- [ ] No critical errors: `docker compose logs | grep -i "error\|failed\|exception" | wc -l` < 3

### ✅ Before Running Full Stack

- [ ] Backend & Agent-Service images built: `docker images | grep careerpilot`
- [ ] No dangling images: `docker images --filter "dangling=true"` returns empty
- [ ] Disk space available: `docker system df | grep "Local Volumes"`
- [ ] Network configured: `docker network ls | grep careerpilot`

---

## Troubleshooting

### ❌ Issue: "docker compose: command not found"

**Cause**: docker-compose not installed or using old version

**Fix**:
```bash
# Check version
docker compose --version
# Should show v2.x or higher

# On Linux, may need to install as plugin
docker compose version  # If this works, you have v2

# On Windows/Mac, install Docker Desktop (includes compose v2)
# https://www.docker.com/products/docker-desktop
```

---

### ❌ Issue: Port Already in Use (EADDRINUSE)

**Error message**: `Bind for 0.0.0.0:5432 failed: port is already allocated`

**Fix**:
```bash
# Find process on port
lsof -i :5432

# Kill it
kill -9 <PID>

# Or stop conflicting docker container
docker ps | grep 5432
docker stop <container-id>

# Then retry
docker compose up -d
```

---

### ❌ Issue: Container Exits Immediately

**Status**: Shows "Exited (1)" instead of "Up"

**Debug**:
```bash
# Check logs for error
docker compose logs <service>

# May show config error, missing env var, or connection failure
```

**Common causes**:
1. Missing environment variable → Add to .env
2. Port conflict → Kill process on that port
3. Missing volume → Check docker-compose.yml
4. Database not ready → Wait and retry: `sleep 10 && docker compose up -d`

---

### ❌ Issue: PostgreSQL Connection Refused

**Error**: `psql: error: connection to server at "localhost" ... refused`

**Likely cause**: DATABASE_URL uses wrong format or PostgreSQL not responding

**Fix**:
```bash
# Verify DATABASE_URL is in Neon DIRECT format (no -pooler)
grep DATABASE_URL .env | grep -v "^#"

# If local Postgres in Docker:
docker compose logs db | tail -20  # Look for "ready to accept"

# Wait longer and retry
sleep 30
docker compose exec db psql -U postgres -c "SELECT 1"
```

---

### ❌ Issue: Kafka Broker Not Ready

**Error in logs**: `UnknownHostException: kafka`, `Connection refused`

**Fix**:
```bash
# Check Kafka running
docker compose ps | grep kafka

# View Kafka logs
docker compose logs kafka | tail -30

# Restart Kafka
docker compose restart zookeeper kafka

# Wait and verify
sleep 10
docker compose exec kafka kafka-topics --bootstrap-server localhost:9092 --list
```

---

### ❌ Issue: Redis Connection Timeout

**Error**: `Connection refused: (111, 'Connection refused')`

**Fix**:
```bash
# Check Redis container
docker compose ps | grep redis

# Restart it
docker compose restart redis

# Verify
docker compose exec redis redis-cli PING
```

---

### ❌ Issue: Out of Disk Space

**Error**: `no space left on device`

**Fix**:
```bash
# Check disk usage
docker system df

# Remove old images/containers/volumes
docker system prune -a

# If still needed, remove specific items
docker volume rm <volume-id>
docker image rm <image-id>

# May need to clean up outside Docker
# Check disk: df -h
```

---

### ❌ Issue: Container Logs Show "OOMKilled"

**Cause**: Container ran out of memory

**Fix**:
```bash
# Check resource usage
docker stats

# Increase Docker memory limit
# Settings → Resources → Memory Slider
# Recommend: 4GB minimum, 8GB for smooth operation

# Or limit specific service in docker-compose.yml:
# services:
#   backend:
#     mem_limit: 2g
```

---

### ❌ Issue: MinIO Console Won't Load

**Error**: http://localhost:9001 shows error or blank page

**Fix**:
```bash
# Check MinIO running
docker compose ps | grep minio

# Check logs
docker compose logs minio | tail -20

# Verify it's responding
curl http://localhost:9001/minio/health

# Restart
docker compose restart minio

# Default credentials: minioadmin / minioadmin
```

---

### ❌ Issue: "Unable to reach agent-service"

**Error from backend logs**: `Unable to reach agent service`

**Fix**:
```bash
# Check agent-service running
docker compose ps | grep agent-service

# Verify it's responding
curl http://localhost:8088/health

# Check backend has correct URL
docker compose exec backend env | grep AGENT_SERVICE_URL
# Should be: AGENT_SERVICE_URL=http://agent-service:8088

# If using localhost instead of agent-service:
# Edit .env or docker-compose.yml
# backend can't reach localhost:8088, must use service name
```

---

## Tips & Best Practices

1. **Always set env-file**: `docker compose --env-file .env up -d` (not optional)
2. **Watch logs during startup**: `docker compose logs -f` to see initialization
3. **Reset on major changes**: `docker compose down -v && docker compose up -d`
4. **Backup data before down -v**: Volumes are deleted, data is lost
5. **Use service names internally**: From backend, reach agent-service at `http://agent-service:8088`, not `localhost`
6. **Monitor disk**: Large databases grow fast, check `docker system df` weekly
7. **Rebuild after code changes**: Always rebuild images: `docker compose build --no-cache <service>`

---

**Status**: 🟢 Ready  
**Last Updated**: 2026-06-20
