# Agent Task Console Implementation Plan

> **For agentic workers:** Execute this plan task-by-task with tests first.

**Goal:** Replace the no-op sample entry with a usable task-first Agent demo that visibly runs Planner, Coder, Ops, and Reviewer stages.

**Architecture:** Keep the existing `POST /api/runs` protocol and immutable `AgentState`. Add a deterministic `demo-agent` GraphFactory whose four named nodes derive visible state artifacts from a natural-language task, then change the launcher to translate a task form into the exact state payload. Preserve raw JSON as an advanced debug toggle.

**Tech Stack:** Java 21, Spring Boot, StateGraph, React, TypeScript, Vitest.

---

### Task 1: Define the demo graph contract with failing tests

**Files:**
- Test: `agent-web/src/test/java/com/agent/web/config/SampleGraphConfigurationTest.java`
- Test: `agent-web/src/main/frontend/src/components/RunLauncher.test.tsx`

- [ ] Assert the graph id is `demo-agent`, execution trace contains `planner`, `coder`, `ops`, `reviewer`, and variables expose task, plan, diff, exit code, and review result.
- [ ] Assert the launcher renders a task input and starts with the exact `demo-agent` payload when submitted.

### Task 2: Implement the deterministic end-to-end demo graph

**Files:**
- Modify: `agent-web/src/main/java/com/agent/web/config/SampleGraphConfiguration.java`

- [ ] Register `demo-agent` as the default graph.
- [ ] Implement four sequential nodes that preserve the task and emit inspectable Planner/Coder/Ops/Reviewer variables, including a valid Unified Diff and terminal output.

### Task 3: Replace raw JSON as the primary launcher

**Files:**
- Modify: `agent-web/src/main/frontend/src/components/RunLauncher.tsx`
- Modify: `agent-web/src/main/frontend/src/components/Workbench.tsx`
- Modify: `agent-web/src/main/frontend/src/styles.css`

- [ ] Add a required natural-language task textarea, workspace label, and primary action text.
- [ ] Default graph id to `demo-agent`; make graph id and raw JSON an advanced details section.
- [ ] Show current agent stage and task summary in the workbench.

### Task 4: Verify and document the user flow

**Files:**
- Modify: `README.md`

- [ ] Document the exact first-run task flow and distinguish `demo-agent` from production graph registration.
- [ ] Run frontend tests, Java tests, full Maven verification, Compose config validation, and diff checks.
- [ ] Commit as `feat(web): expose task-first agent execution workflow`.

