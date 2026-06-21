# Spring Boot Backend Skill

## Purpose
Run, test, debug, and build a Spring Boot service from the command line. Generic to any
Spring Boot project — repository-specific commands (exact env vars, ports, service names)
live in that repo's CLAUDE.md / project memory, not here.

---

## Workflows

### Workflow: Run Locally (Development Mode)

```bash
cd <service-dir>

# First time, or after dependency changes
mvn clean compile

# Run application
mvn spring-boot:run
```

**Prerequisites**: any external dependencies the app needs (database, message broker, cache,
object storage) must be reachable, and required env vars / `application.yml` properties set —
check the project's own docs for the specific list.

**Expected startup signal**: a log line like `Started <Application> in X.XXX seconds`.

**Verify running**: hit any unauthenticated health/info endpoint the app exposes (Actuator's
`/actuator/health` if enabled, or a custom diagnostics endpoint).

---

### Workflow: Run a Single Test

```bash
mvn -Dtest=ClassName#methodName test
mvn -Dtest=ClassName test          # all tests in one class
mvn test -X                         # verbose/debug output
```

---

### Workflow: Run All Tests

```bash
mvn clean test
mvn -DskipTests clean compile       # skip tests when you just need to compile
```

---

### Workflow: Build a Deployable JAR

```bash
mvn clean package -DskipTests
# Output: target/<artifact>-<version>.jar
```

---

### Workflow: Attach a Remote Debugger

```bash
mvn -Dspring-boot.run.jvmArguments="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=5005" spring-boot:run
```

IntelliJ: Run → Edit Configurations → Add Remote JVM Debug → host `localhost`, port `5005`.
VS Code: install "Debugger for Java", create a `launch.json` entry with `"request": "attach"`,
`"hostName": "localhost"`, `"port": 5005`.

---

### Workflow: Inspect Dependencies

```bash
mvn dependency:tree
mvn dependency:tree -Dverbose | grep -i "conflict"
```

---

### Workflow: Clean & Rebuild

```bash
rm -rf target
mvn clean compile
```

---

## Checklists

### ✅ Pre-Run

- [ ] External dependencies (DB, broker, cache, etc.) reachable
- [ ] Required env vars / config properties set and non-empty
- [ ] Maven 3.9+: `mvn -v`
- [ ] Correct JDK on PATH: `java -version` matches the project's required version
- [ ] Target port free: nothing else already bound to the app's configured port

### ✅ Post-Startup Verification

- [ ] Startup log shows the app actually started (no exception stack trace at the end)
- [ ] Health/diagnostics endpoint responds 200
- [ ] No "connection refused"/timeout errors for any dependency in the first 50 log lines
- [ ] No migration-tool errors (Flyway/Liquibase) if the project uses one

### ✅ Before Committing

- [ ] `mvn clean compile` succeeds with no new warnings
- [ ] `mvn test` green
- [ ] No unused imports / dead code in changed files
- [ ] Controllers return response DTOs, not JPA entities or raw `JsonNode` — Jackson cannot
      reliably serialize entity/JsonNode graphs and this is one of the most common Spring
      Boot REST bugs
- [ ] No blank/swallowed `catch` blocks

---

## Troubleshooting

### ❌ Port Already in Use

`Address already in use` → find and kill the process holding the port, or change
`server.port` in `application.yml`.

### ❌ Database Connection Refused / Auth Failed

1. Confirm the connection string/host is reachable from this machine (not just from inside a
   container network).
2. Test the connection directly with the DB's own CLI client before blaming the app.
3. If using a pooled/proxy endpoint (e.g. a serverless DB's pooler), some tools (Flyway,
   prepared-statement-heavy drivers) break under transaction-mode pooling — try the direct
   endpoint.

### ❌ Migration Tool Error (Flyway/Liquibase)

Usually means the schema already has objects the migration tool doesn't know about (no
migration-history table yet). Most migration tools have a "baseline on first run" option —
check if it's enabled. As a last resort in a throwaway dev environment, drop and recreate the
schema, never in anything with real data.

### ❌ Message Broker / Cache Connection Failed

Confirm the broker/cache container or service is actually running and that the app's
configured host:port matches how it's reachable from where the app is running (a container
talking to another container needs the service name, not `localhost`).

### ❌ OutOfMemoryError

```bash
export MAVEN_OPTS="-Xmx2g"
```
or pass `-Xmx` via the run command's JVM args.

### ❌ "Cannot Find Symbol" After Adding a New Class/DTO

```bash
rm -rf target
mvn clean compile
```
Usually a stale incremental-compile artifact, not a real code error — verify the file is
actually saved and tracked (`git status`) before debugging further.

### ❌ Jackson "Type Definition Error" on a JSON Field

Almost always means a controller is returning a JPA entity or a raw `JsonNode`/`ObjectNode`
field directly. Fix: return a response record/DTO, parsing any JSON-typed entity columns into
a `Map`/POJO before constructing the DTO.

### ❌ Application Hangs at Startup With No New Logs

Run with `mvn -X spring-boot:run` for verbose output and check whatever the last log line was
waiting on — almost always a slow or unreachable dependency (DB, broker, external API call
during a `@PostConstruct`/`ApplicationRunner`).

---

## Tips & Best Practices

1. Run `mvn clean compile` after any dependency or base-class change — incremental compiles
   can hide breakage.
2. Logs are the first signal of trouble — read them before reading the stack trace.
3. Kill stray `spring-boot:run` processes before starting a new one (`pkill -f spring-boot:run`
   on Unix, or find/kill the PID on Windows) — duplicate instances fight over the same port.
4. Prefer fixing the root cause over `-DskipTests`; only skip tests for builds where tests
   genuinely aren't relevant (e.g. a throwaway compile check).
