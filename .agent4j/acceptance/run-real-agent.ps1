$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..\..\")).Path
$envFile = Join-Path $repoRoot ".env"
$fixture = Join-Path $PSScriptRoot "square-root-fix"
$evidence = Join-Path $PSScriptRoot "evidence"
New-Item -ItemType Directory -Force -Path $evidence | Out-Null

$enabled = Get-Content -LiteralPath $envFile | Where-Object { $_ -eq "AGENT_LLM_ENABLED=true" }
if ($enabled -ne "AGENT_LLM_ENABLED=true") {
    throw "AGENT_LLM_ENABLED 必须精确为 true"
}

function Invoke-Json([string] $method, [string] $uri, $body) {
    $params = @{ Method = $method; Uri = "http://localhost:8080$uri"; UseBasicParsing = $true }
    if ($null -ne $body) {
        $params.ContentType = "application/json"
        $params.Body = ($body | ConvertTo-Json -Depth 20 -Compress)
    }
    Invoke-RestMethod @params
}

$readiness = Invoke-WebRequest -Uri "http://localhost:8080/actuator/health/readiness" -UseBasicParsing
$readiness.Content | Set-Content -LiteralPath (Join-Path $evidence "readiness.json")
if ($readiness.StatusCode -ne 200) { throw "readiness 不是 200" }

$archive = Join-Path $evidence "square-root-fix.zip"
if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive -Force }
Compress-Archive -Path (Join-Path $fixture "*") -DestinationPath $archive -Force
Push-Location $fixture
try {
    mvn test | Tee-Object -FilePath (Join-Path $evidence "fixture-initial-failing-test.txt")
    if ($LASTEXITCODE -eq 0) { throw "验收夹具初始测试必须失败" }
} finally {
    Pop-Location
}

$form = @{
    displayName = "square-root-fix"
    repositoryId = "square-root-fix"
    archive = Get-Item -LiteralPath $archive
}
$workspace = Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/workspace-imports" -Form $form
$workspace | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $evidence "workspace.json")
$workspaceId = $workspace.workspaceId

$conversation = Invoke-Json POST "/api/workspaces/$workspaceId/conversations" @{}
$conversation | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $evidence "conversation.json")
$conversationId = $conversation.conversationId

$turn = Invoke-Json POST "/api/conversations/$conversationId/turns" @{ content = "修复 NumberLabel.label 的错误并运行 Maven 测试，最后说明修改了什么。" }
$turn | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $evidence "turn-submitted.json")
$turnId = $turn.turnId

for ($attempt = 1; $attempt -le 120; $attempt++) {
    Start-Sleep -Seconds 2
    $turns = Invoke-Json GET "/api/conversations/$conversationId/turns" $null
    $current = $turns | Where-Object { $_.turnId -eq $turnId }
    $current | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $evidence "turn-final.json")
    if ($current.status -in @("COMPLETED", "FAILED")) { break }
}

$final = Get-Content -Raw (Join-Path $evidence "turn-final.json") | ConvertFrom-Json
if ($final.status -ne "COMPLETED") { throw "真实 Agent 轮次未完成: $($final.status)" }
if ([string]::IsNullOrWhiteSpace($final.assistantContent)) { throw "final_response 为空" }

if ($null -ne $final.runId) {
    $run = Invoke-Json GET "/api/runs/$($final.runId)" $null
    $run | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath (Join-Path $evidence "run-final.json")
    curl.exe --silent --show-error --max-time 15 "http://localhost:8080/api/runs/$($final.runId)/events" | Set-Content -LiteralPath (Join-Path $evidence "trace-sse.txt")
    curl.exe --silent --show-error --max-time 15 "http://localhost:8080/api/runs/$($final.runId)/logs" | Set-Content -LiteralPath (Join-Path $evidence "terminal-sse.txt")
}

$importedFixture = Join-Path $repoRoot ".agent4j\imports\$workspaceId"
Push-Location $importedFixture
try {
    mvn test | Tee-Object -FilePath (Join-Path $evidence "fixture-maven-test.txt")
    if ($LASTEXITCODE -ne 0) { throw "Agent 修复后的 Maven 测试失败" }
} finally {
    Pop-Location
}

Write-Output "workspaceId=$workspaceId"
Write-Output "conversationId=$conversationId"
Write-Output "turnId=$turnId"
Write-Output "runId=$($final.runId)"
Write-Output "evidence=$evidence"
