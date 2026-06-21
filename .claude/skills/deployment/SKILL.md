# Deployment Skill

## Purpose
Build images, deploy a multi-service stack, verify the deployment, and roll back if needed.
Generic across projects — registry URLs, image names, and the specific E2E test command live
in that repo's own docs.

---

## Workflows

### Workflow: Build All Service Images

```bash
docker compose build --no-cache
docker images | grep <project-prefix>     # confirm each expected image was produced
```

If a build fails, re-run just that service with output captured so you can see the actual
failing step rather than the aggregate compose output:

```bash
docker compose build --no-cache <service> 2>&1 | tee build.log
grep -i error build.log
```

---

### Workflow: Build a Single Service

```bash
docker compose build --no-cache <service>
```

---

### Workflow: Deploy (Dev / Staging)

```bash
docker compose build --no-cache
docker compose --env-file .env up -d
docker compose logs -f       # watch startup
docker compose ps            # confirm everything healthy once settled
```

---

### Workflow: Deploy (Production-Like)

```bash
docker compose --env-file .env.prod build --no-cache
docker compose --env-file .env.prod up -d
docker compose --env-file .env.prod ps
docker stats --no-stream     # sanity-check resource usage before declaring success
```

---

### Workflow: Run End-to-End Verification

Use whatever smoke-test harness the project already has (a script, a Postman/Newman
collection, a `curl` sequence through the critical user flow). The shape that matters:

```bash
<project-e2e-command>
echo $?    # 0 = pass
```

If the project doesn't have one yet, the minimum useful smoke test is: each service's health
endpoint returns 200, and the single most important user-facing flow completes end-to-end.

---

### Workflow: Verify Deployment Health

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://<host>:<port>/<health-path>
docker compose logs | grep -i "error\|exception" | wc -l   # should be small/near-zero
```

---

### Workflow: Update One Service in a Running Stack

```bash
docker compose build --no-cache <service>
docker compose up -d <service>
docker compose logs -f <service>     # confirm clean restart
<health-check-for-that-service>
```

---

### Workflow: Roll Back

```bash
git log --oneline -5
git checkout <previous-commit-or-tag>
docker compose build --no-cache
docker compose down
docker compose --env-file .env up -d
docker compose ps
```

---

### Workflow: Push Images to a Registry

```bash
docker tag <local-image>:latest <registry>/<image>:latest
docker login <registry>
docker push <registry>/<image>:latest
```

---

### Workflow: Deploy to an Orchestrator (Kubernetes Example)

```bash
kubectl apply -f <manifests-dir>/
kubectl get pods
kubectl logs <pod-name> -f
kubectl port-forward service/<service> <local-port>:<service-port>
```

---

## Checklists

### ✅ Pre-Build

- [ ] Working tree clean / intended branch checked out
- [ ] Tests passing locally before building images
- [ ] Docker daemon running, sufficient disk space
- [ ] All required env vars set for the target environment

### ✅ Build Phase

- [ ] Every service image builds without `--no-cache` masking a stale-layer issue
- [ ] No warnings in build output that indicate a skipped step (e.g. silently-skipped tests)
- [ ] Expected number of images present afterward

### ✅ Deployment Phase

- [ ] Stack starts cleanly (`docker compose up -d` exits 0)
- [ ] All services report running/healthy
- [ ] No startup errors in the first couple minutes of logs
- [ ] Each service's health endpoint responds 200

### ✅ Verification Phase

- [ ] E2E/smoke test passes end-to-end
- [ ] Error-level log lines stay near zero post-startup
- [ ] Resource usage (CPU/memory) is within expected bounds for the load tested

### ✅ Post-Deployment

- [ ] Tag the release if this is a versioned deploy
- [ ] Record what changed and when (changelog, deployment log, or commit message)
- [ ] Watch logs/metrics for a reasonable window after deploy before considering it done

---

## Troubleshooting

### ❌ Language/Toolchain Build Step Fails Inside the Image

Capture the build output to a file and grep for the actual error — the failing step (compiler
error, missing dependency, failed test run) is almost always visible there even when the
top-level `docker compose build` output is truncated.

```bash
docker compose build --no-cache <service> 2>&1 > build.log
grep -B2 -A5 -i error build.log
```

### ❌ Container Starts but the App Inside Fails

```bash
docker compose logs <service> | tail -50
```
Most common causes: a missing/empty env var, a port already taken inside the container's
network, a dependency (DB/broker) not ready yet, or a config file that wasn't copied into the
image.

### ❌ E2E/Smoke Test Fails

Run it with verbose output if it supports one, identify which step failed, and reproduce that
single step manually (one `curl` call) against the running stack to isolate whether it's a
test-harness issue or a real regression.

### ❌ Services Respond Slowly or Time Out Right After Deploy

Usually means a dependency (DB migration, cache warm-up, broker connection) is still
initializing. Give it more time before declaring failure, and check logs for migration/startup
progress rather than assuming it's broken.

### ❌ Out of Disk Space During Build

```bash
docker system prune -a
docker system df
df -h /        # check host disk too, not just Docker's data
```

### ❌ Registry Push Denied / Auth Error

Re-authenticate to the registry (`docker login`, or the cloud provider's CLI login command),
and confirm the image tag actually includes the registry's full path.

---

## Tips & Best Practices

1. Use `--no-cache` for anything you're about to ship — cached layers can hide a dependency
   change that should have invalidated them.
2. Run the full E2E/smoke test locally before pushing to a registry or deploying further.
3. Tag releases explicitly rather than relying on `latest` once you care about reproducing a
   specific deployed version.
4. Watch logs for a window after every deploy — don't declare success the instant containers
   report "running."
5. Always have a rollback path identified (a known-good commit/tag) before deploying a risky
   change.
6. Database/schema migrations usually run automatically on startup — verify the
   migration-history table/log shows what you expect rather than assuming silence means success.
