# Real Conversation Continuity EDD Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an explicit, real-LLM black-box gate that proves two turns in one persisted conversation retain the first turn's facts.

**Architecture:** A focused PowerShell acceptance script reuses the workspace created by the existing imported-project EDD, calls the production Conversation REST API twice, polls authoritative Turn state, validates both Runs, and writes ignored JSON evidence. Production Java and API contracts remain unchanged.

**Tech Stack:** PowerShell 7, Spring Boot REST API, PostgreSQL-backed Conversation/Run state, existing Docker Compose service.

---

### Task 1: Add the real continuity EDD script

**Files:**
- Create: `.agent4j/acceptance/run-conversation-continuity.ps1`
- Modify: `.gitignore`

- [x] **Step 1: Run the missing-script red gate**

```powershell
if (-not (Test-Path '.agent4j\acceptance\run-conversation-continuity.ps1')) {
    Write-Error 'RED: continuity EDD script is not present'
    exit 1
}
```

Expected: exit code `1` with `RED: continuity EDD script is not present`.

- [x] **Step 2: Implement strict configuration and API helpers**

Read `.env` with ordinal key matching, require `AGENT_LLM_ENABLED=true`, load the exact `workspaceId`
from `.agent4j/acceptance/evidence/workspace.json`, and define bounded `Invoke-AgentJson` and
`Wait-ConversationTurn` functions. A terminal `FAILED` Turn or a 180-second deadline throws with the
exact Turn status. Add only `!.agent4j/acceptance/run-conversation-continuity.ps1` to `.gitignore`;
all generated evidence remains ignored.

- [x] **Step 3: Execute and validate two turns**

Create one Conversation. The first turn establishes `新余高新区` and `电瓶车`; the second turn asks
the model to restate both facts before answering. Require two ordered Turn records, distinct non-null
Run IDs, `COMPLETED` Run states, and second-answer containment of both exact facts.

- [x] **Step 4: Persist ignored evidence**

Write `conversation-continuity.json`, `conversation-continuity-turns.json`, and two Run JSON files under
`.agent4j/acceptance/evidence/`. Verify the audit log contains both Turn IDs and Run IDs without printing
the API key.

### Task 2: Document and verify the gate

**Files:**
- Modify: `README.md`
- Modify: `docs/ENGINEERING_PITFALLS.md`

- [x] **Step 1: Document the command**

Add the exact command after the imported-project acceptance command:

```powershell
pwsh .agent4j/acceptance/run-conversation-continuity.ps1
```

- [x] **Step 2: Run syntax and real black-box verification**

```powershell
$errors = $null
[System.Management.Automation.Language.Parser]::ParseFile(
    (Resolve-Path '.agent4j/acceptance/run-conversation-continuity.ps1'),
    [ref]$null,
    [ref]$errors) | Out-Null
if ($errors.Count -ne 0) { throw ($errors | Out-String) }
pwsh .agent4j/acceptance/run-conversation-continuity.ps1
```

Expected: parser errors `0`; both Turns and Runs are `COMPLETED`; output contains Conversation, Turn,
and Run IDs plus the evidence directory.

- [x] **Step 3: Run repository gates**

```powershell
git diff --check
git status --short
```

Expected: no whitespace errors; only the script and documentation files are modified.

- [x] **Step 4: Commit atomically**

```powershell
git add .gitignore .agent4j/acceptance/run-conversation-continuity.ps1 README.md docs/ENGINEERING_PITFALLS.md
git commit -m "test(eval): verify real conversation continuity"
```
