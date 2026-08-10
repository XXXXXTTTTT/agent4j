# Workspace Import And Real Agent Acceptance Implementation Plan

> For agentic workers: use the executing-plans workflow to implement this plan task-by-task with review checkpoints.

Goal: Add a mounted-directory picker and secure external-folder ZIP import to the Web workbench, then verify a real LLM Agent run on an isolated Java project.

Architecture: Keep WorkspaceAccessService as the path and membership authority. Add focused directory browsing and archive import services under com.agent.web.workspace. Controllers expose exact JSON and ProblemDetail contracts. The React dialog consumes those contracts while the existing conversation/run graph remains unchanged.

Tech Stack: Java 21, Spring WebFlux multipart, java.util.zip, PostgreSQL workspace repository, React 19, Vitest, fflate, Docker Compose, real OpenAI-compatible SSE endpoint.

---

### Task 1: Bounded Workspace Directory Browsing

Files:
- Create agent-web/src/main/java/com/agent/web/workspace/WorkspaceDirectoryEntry.java
- Create agent-web/src/main/java/com/agent/web/workspace/WorkspaceDirectoryListing.java
- Create agent-web/src/main/java/com/agent/web/workspace/WorkspaceDirectoryBrowser.java
- Create agent-web/src/main/java/com/agent/web/controller/WorkspaceDirectoryView.java
- Create agent-web/src/main/java/com/agent/web/controller/WorkspaceDirectoryController.java
- Test agent-web/src/test/java/com/agent/web/workspace/WorkspaceDirectoryBrowserTest.java
- Test agent-web/src/test/java/com/agent/web/controller/WorkspaceDirectoryControllerTest.java

- [ ] Step 1: Write a failing test that creates a temporary configured root, a child directory, and a regular file, then asserts WorkspaceDirectoryBrowser.browse(root) returns only the child directory.
- [ ] Step 2: Add a failing test that browses a sibling directory and asserts IllegalArgumentException with the exact message workspacePath 必须位于配置工作区内.
- [ ] Step 3: Run mvn -pl agent-web -am -Dfrontend.skip=true -Dtest=WorkspaceDirectoryBrowserTest -Dsurefire.failIfNoSpecifiedTests=false test; expected result is compilation failure because the browser class is absent.
- [ ] Step 4: Implement the browser using WorkspaceAccessService path validation, Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS), toRealPath, root containment checks, immutable results, and name sorting.
- [ ] Step 5: Add a WebFlux controller test for GET /api/workspace-directories?path=...; assert exact JSON keys currentPath, parentPath, and entries[].name/path, plus HTTP 400 for an external path.
- [ ] Step 6: Run the focused test until green, run git diff --check, then commit feat(web): browse mounted workspace directories.

### Task 2: Secure ZIP Project Import

Files:
- Create agent-web/src/main/java/com/agent/web/config/WorkspaceImportProperties.java
- Create agent-web/src/main/java/com/agent/web/workspace/WorkspaceImportService.java
- Create agent-web/src/main/java/com/agent/web/controller/WorkspaceImportController.java
- Modify agent-web/src/main/java/com/agent/web/config/HarnessConfiguration.java
- Modify agent-web/src/main/resources/application.properties
- Test agent-web/src/test/java/com/agent/web/workspace/WorkspaceImportServiceTest.java
- Test agent-web/src/test/java/com/agent/web/controller/WorkspaceImportControllerTest.java

- [ ] Step 1: Write real ZIP tests for normal extraction, absolute entry rejection, ../ traversal rejection, duplicate normalized paths, archive byte limit, extracted byte limit, file count limit, and cleanup after repository registration failure. Build archives with ZipOutputStream; do not mock the ZIP parser.
- [ ] Step 2: Run mvn -pl agent-web -am -Dfrontend.skip=true -Dtest=WorkspaceImportServiceTest -Dsurefire.failIfNoSpecifiedTests=false test; expected result is compilation failure because the import service is absent.
- [ ] Step 3: Implement WorkspaceImportProperties(maxArchiveBytes, maxExtractedBytes, maxFiles) and stream the uploaded archive to staging before extraction.
- [ ] Step 4: Normalize every ZipEntry against staging, reject paths outside staging, duplicate targets, empty names, symlink-like entries, non-ZIP content, file/byte limits, and existing destination IDs. Write with CREATE_NEW, then move staging to .agent4j/imports/<workspaceId>.
- [ ] Step 5: Roll back staging and final paths on every failure. Emit WORKSPACE_IMPORT_COMPLETED or WORKSPACE_IMPORT_FAILED to logger com.agent.audit.workspace with user, workspace, counts, bytes, status, error type, and time; never log file contents or secrets.
- [ ] Step 6: Add the multipart controller, map malformed input to 400, size limits to 413, and destination conflicts to 409. Run service and controller tests, then commit feat(web): import bounded workspace archives.

### Task 3: Web Project Picker And Folder Import

Files:
- Modify agent-web/src/main/frontend/package.json and package-lock.json
- Modify agent-web/src/main/frontend/src/api/contracts.ts
- Modify agent-web/src/main/frontend/src/api/conversationApi.ts
- Modify agent-web/src/main/frontend/src/hooks/useConversationWorkspace.ts
- Modify agent-web/src/main/frontend/src/components/WorkspaceDialog.tsx
- Modify agent-web/src/main/frontend/src/components/Workbench.tsx and styles.css
- Test existing conversation API, dialog, hook, and Workbench test files

- [ ] Step 1: Add red tests for exact directory response decoding, directory browsing requests, import FormData fields, directory navigation, selected-directory autofill, and external folder file count/bytes.
- [ ] Step 2: Run npm run test:run -- src/api/conversationApi.test.ts src/components/WorkspaceDialog.test.tsx src/hooks/useConversationWorkspace.test.tsx; expected result is failures for missing contracts and controls.
- [ ] Step 3: Add WorkspaceDirectoryEntry, WorkspaceDirectoryListing, browseWorkspaceDirectories(path), and importWorkspace(command) with exact-key decoding. Use FormData fields displayName, repositoryId, and ZIP archive.
- [ ] Step 4: Replace manual path entry with two tabs: mounted directory navigation and input type=file webkitdirectory multiple. Generate ZIP only in the submit branch, derive count and bytes during render, disable close/re-submit while uploading, and show ProblemDetail detail.
- [ ] Step 5: Extend the hook and Workbench API interfaces; after successful create/import update the workspace list, select the returned workspace, and preserve the URL selection.
- [ ] Step 6: Run npm run test:run and npm run build, then commit feat(web): add project picker and folder import.

### Task 4: Configuration And Documentation

Files:
- Modify agent-web/src/main/resources/application.properties
- Modify .gitignore
- Modify README.md
- Test existing production properties/configuration tests

- [ ] Step 1: Add defaults for archive bytes 52428800, extracted bytes 104857600, and files 5000, with environment overrides AGENT_WORKSPACE_IMPORT_MAX_ARCHIVE_BYTES, AGENT_WORKSPACE_IMPORT_MAX_EXTRACTED_BYTES, and AGENT_WORKSPACE_IMPORT_MAX_FILES.
- [ ] Step 2: Add .agent4j/ to .gitignore and document mounted selection, browser folder import, and CLI zero-copy mounting.
- [ ] Step 3: Run git diff --check and mvn -pl agent-web -am -Dfrontend.skip=true test, then commit docs(web): document project selection and import.

### Task 5: Real LLM Agent Acceptance

Files:
- Create ignored fixture .agent4j/acceptance/square-root-fix/
- Create .agent4j/acceptance/run-real-agent.ps1
- Do not modify agent-core node logic for this acceptance task.

- [ ] Step 1: Create a minimal Maven Java project with pom.xml, src/main/java/demo/NumberLabel.java, and src/test/java/demo/NumberLabelTest.java; the initial test must fail and the task must ask the Agent to correct the method and run mvn test.
- [ ] Step 2: Assert the saved .env has AGENT_LLM_ENABLED=true without printing keys, then run mvn -pl agent-web -am package -DskipTests and docker compose -f docker-compose.local.yml --env-file .env up -d --build.
- [ ] Step 3: Import or select the fixture, capture returned workspaceId and conversationId, and verify it exists under /agent-workspace/.agent4j/imports/<workspaceId>.
- [ ] Step 4: Submit the exact task: 修复 NumberLabel.label 的错误并运行 Maven 测试，最后说明修改了什么。 Collect turn status, Run status, Trace SSE, terminal SSE, changed files, test exit code, and non-empty final_response.
- [ ] Step 5: Search mounted logs for captured IDs and exact logger markers; confirm LLM URL/model/status/duration, workspace import event, conversation events, and absence of API key text.
- [ ] Step 6: Run git diff --check and git status --short, then commit test(eval): add real workspace agent acceptance harness. Final evidence must include readiness 200, a real LLM request, completed turn, test exit 0, Trace events, and audit JSON lines.

