# Chapter 25 Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make both Docker deployment modes observable, bounded, gracefully stoppable, migration-aware, and backed by a reproducible PostgreSQL recovery runbook.

**Architecture:** Spring Boot Actuator owns process and database health groups. Compose waits for PostgreSQL and probes Agent readiness. Docker entrypoints forward termination signals with `exec`; Compose resource limits are explicit and identical across local/production modes. A deterministic EDD checks the deployment contract without calling an LLM.

**Tech Stack:** Java 21, Spring Boot Actuator 3.3.x, Docker Compose, PostgreSQL/pgvector, JUnit 5.

---

### Task 1: Health and graceful-shutdown configuration

**Files:**
- Modify: `agent-web/pom.xml`
- Modify: `agent-web/src/main/resources/application.properties`
- Create: `agent-web/src/test/java/com/agent/web/deployment/ActuatorHealthConfigurationTest.java`

- [ ] **Step 1: Write the failing test**

Assert the application properties expose `/actuator/health/liveness` and `/actuator/health/readiness`, readiness includes `readinessState,db`, and graceful shutdown timeout is `30s`.

- [ ] **Step 2: Run the test and verify it fails**

Run: `mvn -pl agent-web -am -Dtest=ActuatorHealthConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because Actuator and the deployment properties are absent.

- [ ] **Step 3: Implement the minimum configuration**

Add `spring-boot-starter-actuator`; set `management.endpoint.health.probes.enabled=true`, `management.endpoints.web.exposure.include=health,info`, `management.endpoint.health.group.readiness.include=readinessState,db`, `server.shutdown=graceful`, and `spring.lifecycle.timeout-per-shutdown-phase=30s`.

- [ ] **Step 4: Run the test and verify it passes**

Run the same Maven command; expected `Tests run: 1, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

`git add agent-web/pom.xml agent-web/src/main/resources/application.properties agent-web/src/test/java/com/agent/web/deployment/ActuatorHealthConfigurationTest.java && git commit -m "feat(deployment): expose health probes and graceful shutdown"`

### Task 2: Harden runtime images and Compose contracts

**Files:**
- Modify: `Dockerfile`
- Modify: `Dockerfile.local`
- Modify: `docker-compose.yml`
- Modify: `docker-compose.local.yml`

- [ ] **Step 1: Write the failing deployment EDD assertions**

Create assertions for Java 21 runtime images, `exec java` entrypoints, curl readiness probes, explicit `cpus`, `mem_limit`, and `pids_limit` in both Compose files.

- [ ] **Step 2: Run the EDD and verify it fails**

Run: `mvn -pl agent-eval -am -Dtest=DeploymentEddTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL on missing probe/resource/entrypoint assertions.

- [ ] **Step 3: Implement the deployment contract**

Install curl in both runtime images, change entrypoints to `exec java $JAVA_OPTS -jar app.jar`, add Agent readiness healthchecks after PostgreSQL dependency, and set `cpus: 2.0`, `mem_limit: 2g`, `pids_limit: 512` for Agent services. Keep existing workspace, Docker socket, and log mounts unchanged.

- [ ] **Step 4: Verify Compose syntax**

Run `docker compose -f docker-compose.local.yml --env-file .env config` and `docker compose -f docker-compose.yml --env-file .env config`; both must exit `0`.

- [ ] **Step 5: Run the EDD and commit**

Run the focused EDD again, then commit with `feat(deployment): bound compose runtime and readiness probes`.

### Task 3: Backup/recovery runbook and deployment verification

**Files:**
- Create: `docs/deployment/backup-recovery.md`
- Modify: `docs/ENGINEERING_PITFALLS.md`
- Modify: `agent-eval/src/test/java/com/agent/eval/DeploymentEddTest.java`
- Modify: `docs/superpowers/plans/2026-08-09-guide-eighth-part-25-deployment.md`

- [ ] **Step 1: Add the runbook**

Document exact `docker compose exec -T postgres pg_dump`, checksum storage, restore into a disposable database, Flyway validation, and read-only verification queries. State that `.env` and dump files stay outside Git.

- [ ] **Step 2: Add deterministic EDD evidence**

Write `agent-eval/target/edd/deployment-chapter-25.json` with the checked files, assertion IDs, pass count, and `modelCallAttempts=0`.

- [ ] **Step 3: Update pitfalls**

Record the signal-forwarding, readiness-before-traffic, resource-limit, and backup/recovery lessons with only command/test evidence.

- [ ] **Step 4: Run all verification**

Use JDK 21 and run `mvn -pl agent-core,agent-web,agent-eval -am test`, `mvn clean package -DskipTests -Dfrontend.skip=true`, both Compose config commands, and `git diff --check`.

- [ ] **Step 5: Commit and merge**

Commit the plan/doc/EDD updates, fast-forward the feature branch into `master`, rerun the deployment-focused tests on merged `master`, remove only the chapter 25 worktree and branch, and run `git worktree prune`.

