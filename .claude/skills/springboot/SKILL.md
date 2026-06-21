# Spring Boot Backend Skill

## Purpose
Manage Spring Boot 4 / Java 25 backend development: run locally, debug, test, build JAR, fix common issues.

---

## Workflows

### Workflow: Run Backend Locally (Development Mode)

```bash
cd backend

# First time or after pom.xml changes
mvn clean compile

# Run application
mvn spring-boot:run
```

**Prerequisites**:
- Docker services running: `docker compose ps`
- PostgreSQL accessible: `psql $DATABASE_URL -c "SELECT 1"`
- Kafka, Redis, MinIO up and responding
- `.env` file with: `JWT_SECRET` (≥32 chars), `GEMINI_API_KEY`

**Expected startup output**:
```
[main] INFO ... Started CareerPilotBackendApplication in X.XXX seconds
[main] INFO ... Server started on port 8080
```

**Verify running**:
```bash
curl http://localhost:8080/api/diagnostics/ai
# Returns 200 OK with provider health
```

---

### Workflow: Run Single Test

```bash
cd backend

# Run specific test
mvn -Dtest=ClassName#methodName test

# Examples
mvn -Dtest=AuthServiceTest#testRegisterUser test
mvn -Dtest=WorkflowServiceTest test  # All tests in class

# Show output
mvn test -X  # Debug mode (verbose)
```

---

### Workflow: Run All Tests

```bash
cd backend
mvn clean test
```

**Skip tests** (if needed):
```bash
mvn -DskipTests clean compile
```

---

### Workflow: Build Production JAR

```bash
cd backend

# Clean and build
mvn clean package -DskipTests

# Output: backend/target/careerpilot-backend-0.1.0.jar
```

---

### Workflow: Debug Mode (Attach IDE Debugger)

```bash
cd backend

# Start with debug port 5005
mvn -Dspring-boot.run.jvmArguments="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=5005" spring-boot:run
```

**In IDE**:
- IntelliJ: Run → Edit Configurations → Add Remote → Host: localhost, Port: 5005
- VS Code: Install "Debugger for Java", create launch.json with:
```json
{
  "type": "java",
  "name": "Attach to Backend",
  "request": "attach",
  "hostName": "localhost",
  "port": 5005
}
```

---

### Workflow: Check Dependencies

```bash
cd backend

# Show dependency tree
mvn dependency:tree

# Find conflicts
mvn dependency:tree -DoutputFile=dependencies.txt
grep "version conflicts" dependencies.txt
```

---

### Workflow: Clean & Rebuild (Full Reset)

```bash
cd backend

# Remove all build artifacts
rm -rf target

# Full clean build
mvn clean compile
```

---

## Checklists

### ✅ Pre-Run Checklist

- [ ] Docker services running: `docker compose ps` (all RUNNING)
- [ ] PostgreSQL accessible: `psql $DATABASE_URL -c "SELECT 1"` returns 1
- [ ] Kafka broker health: `docker compose logs kafka | grep "started"`
- [ ] Redis responsive: `docker compose exec redis redis-cli PING` returns PONG
- [ ] MinIO up: `curl http://localhost:9001/minio/health` returns 200
- [ ] Maven 3.9+ installed: `mvn -v` shows 3.9 or higher
- [ ] Java 25 installed: `java -version` shows 25.x
- [ ] `.env` file exists: `ls .env`
- [ ] `JWT_SECRET` set and ≥32 chars: `wc -c <(grep JWT_SECRET .env | cut -d= -f2)`
- [ ] `GEMINI_API_KEY` set: `grep GEMINI_API_KEY .env | grep -v "^#"`
- [ ] `DATABASE_URL` uses DIRECT endpoint (no -pooler): `grep DATABASE_URL .env | grep -v pooler`
- [ ] Port 8080 free: `lsof -i :8080` returns empty

### ✅ Post-Startup Verification

- [ ] App started: Last logs show "Started CareerPilotBackendApplication"
- [ ] Health check responds: `curl http://localhost:8080/api/diagnostics/ai` returns 200 OK
- [ ] No database errors: Logs don't show "Connection refused" or "Connection timeout"
- [ ] No migration errors: Logs don't show "Flyway migration failed"
- [ ] Kafka topics ready: Logs show "Cluster metadata update completed"
- [ ] Last 50 logs clean: `docker compose logs backend | tail -50 | grep -i "error" | wc -l` = 0

### ✅ Before Committing Code

- [ ] Code compiles: `mvn clean compile` succeeds
- [ ] Tests pass: `mvn test` shows all green
- [ ] No new warnings: `mvn clean compile 2>&1 | grep -i "warning" | wc -l` = 0
- [ ] Import statements clean: No unused imports
- [ ] Code follows conventions: Package hierarchy is `ai.careerpilot.*`
- [ ] DTOs used for REST: No entities returned from controllers
- [ ] Error handling in place: No blank catch blocks

---

## Troubleshooting

### ❌ Issue: Port 8080 Already in Use

**Error message**: `Address already in use`

**Fix**:
```bash
# Find process on port 8080
lsof -i :8080

# Kill it
kill -9 <PID>

# Or change port in application.yml
# server.port: 9090
```

---

### ❌ Issue: PostgreSQL Connection Failed

**Error message**: `Connection refused`, `FATAL: password authentication failed`

**Checks**:
1. Verify DATABASE_URL uses DIRECT endpoint: `echo $DATABASE_URL | grep -v pooler`
2. Test connection: `psql $DATABASE_URL -c "SELECT 1"`
3. Check Neon status: Log into Neon dashboard, verify project is active

**Fixes**:
```bash
# If using local Postgres, verify running
docker compose exec db psql -U postgres -c "SELECT 1"

# Recreate connection
docker compose down && docker compose up -d db
# Wait 10 seconds for startup
sleep 10

# Then start backend
mvn spring-boot:run
```

---

### ❌ Issue: Flyway Migration Error

**Error message**: `Flyway migration failed`, `Migration V1__init.sql failed`

**Likely cause**: Schema already exists but missing migration history

**Fix**:
```bash
# Check current schema state
psql $DATABASE_URL -c "\dt"

# Flyway will baseline on first run (flyway.baseline-on-migrate: true is set)
# Just restart
mvn spring-boot:run

# If still failing, reset database
psql $DATABASE_URL -c "DROP SCHEMA IF EXISTS public CASCADE;"
psql $DATABASE_URL -c "CREATE SCHEMA public;"
# Then restart backend
```

---

### ❌ Issue: Kafka Connection Failed

**Error message**: `Cannot connect to Kafka broker`, `UnknownHostException`

**Fix**:
```bash
# Check Kafka running
docker compose ps | grep kafka

# If not running
docker compose up -d kafka zookeeper

# Check topic creation
docker compose exec kafka kafka-topics --bootstrap-server localhost:9092 --list

# Verify from backend perspective
# Check application.yml for spring.kafka.bootstrap-servers: kafka:9092
```

---

### ❌ Issue: OutOfMemory Error

**Error message**: `Exception in thread "main" java.lang.OutOfMemoryError`

**Fix**:
```bash
# Increase Maven heap
export MAVEN_OPTS="-Xmx2g"

# Or for spring-boot:run
mvn -DargLine="-Xmx2g" spring-boot:run
```

---

### ❌ Issue: Compilation Error: Cannot Find Symbol

**Error message**: `[ERROR] cannot find symbol - class AgentRunResponse`

**Likely cause**: New DTO file not compiled

**Fix**:
```bash
cd backend

# Clean and recompile
rm -rf target
mvn clean compile

# If still failing, verify file exists
ls src/main/java/ai/careerpilot/api/dto/AgentServiceDtos.java

# Check git status
git status | grep dto
```

---

### ❌ Issue: "Type definition error: JsonNode"

**Error message**: In API response: `Type definition error: [simple type, class com.fasterxml.jackson.databind.JsonNode]`

**Cause**: Returning JsonNode instead of DTO from REST endpoint

**Fix**: See P0_WORKFLOW_FIX.md for details
- Ensure all controllers return DTOs (e.g., `WorkflowRunResponse`, not `JsonNode`)
- Check `AgentServiceClient` is using `AgentRunResponse` not `JsonNode`
- Rebuild: `mvn clean compile`

---

### ❌ Issue: Application Hangs at Startup

**Symptoms**: Startup freezes, no new logs for 2+ minutes

**Likely causes**:
1. Waiting for database connection
2. Waiting for Kafka broker
3. Waiting for external service response

**Debug**:
```bash
# Run with debug output
mvn -X spring-boot:run 2>&1 | tee debug.log

# Find last log entry
tail -20 debug.log

# Common culprits
docker compose logs db        # Database
docker compose logs kafka      # Kafka
docker compose logs redis      # Redis
```

---

### ❌ Issue: High Memory Usage / Slow Compilation

**Fix**:
```bash
# Skip tests
mvn -DskipTests compile

# Use offline mode (if dependencies cached)
mvn -o -DskipTests compile

# Increase Maven heap
export MAVEN_OPTS="-Xmx2g -XX:+UseG1GC"
```

---

## Tips & Best Practices

1. **Always use `mvn clean compile`** when changing dependencies or base classes
2. **Keep tests running in background**: `watch -n 5 'mvn test'`
3. **Check logs early**: First sign of trouble is in logs, not exceptions
4. **Database state matters**: Clean DB between major schema changes: `docker compose down -v`
5. **PORT conflicts**: Multiple instances of backend will fail. Kill old ones: `pkill -f "spring-boot:run"`
6. **Rebuild Docker image**: After pom.xml changes, rebuild: `docker compose build --no-cache backend`

---

**Status**: 🟢 Ready  
**Last Updated**: 2026-06-20
