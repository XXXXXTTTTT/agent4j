$ErrorActionPreference = "Stop"

$repoRoot = (& git -C $PSScriptRoot rev-parse --show-toplevel).Trim()
if ([string]::IsNullOrWhiteSpace($repoRoot)) {
    throw "无法定位 Git 仓库根目录"
}

$envFile = Join-Path $repoRoot ".env"
$evidenceDirectory = Join-Path $PSScriptRoot "evidence"
$workspaceEvidencePath = Join-Path $evidenceDirectory "workspace.json"
$auditLogPath = Join-Path $repoRoot "logs\agent4j-current.log"

if (-not (Test-Path -LiteralPath $envFile)) {
    throw ".env 不存在: $envFile"
}
if (-not (Test-Path -LiteralPath $workspaceEvidencePath)) {
    throw "请先运行 run-real-agent.ps1 生成工作区证据: $workspaceEvidencePath"
}
New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null

$envLines = Get-Content -LiteralPath $envFile -Encoding UTF8
function Get-EnvValue([string] $name) {
    $prefix = "$name="
    $matches = @($envLines | Where-Object {
        $_.StartsWith($prefix, [System.StringComparison]::Ordinal)
    })
    if ($matches.Count -ne 1) {
        throw "$name 必须在 .env 中精确配置一次"
    }
    return $matches[0].Substring($prefix.Length).Trim()
}

if ((Get-EnvValue "AGENT_LLM_ENABLED") -ne "true") {
    throw "AGENT_LLM_ENABLED 必须精确为 true"
}

function Invoke-AgentJson([string] $method, [string] $uri, $body) {
    $parameters = @{
        Method = $method
        Uri = "http://localhost:8080$uri"
        UseBasicParsing = $true
        TimeoutSec = 30
    }
    if ($null -ne $body) {
        $parameters.ContentType = "application/json"
        $parameters.Body = ($body | ConvertTo-Json -Depth 20 -Compress)
    }
    return Invoke-RestMethod @parameters
}

function Wait-ConversationTurn(
        [string] $conversationId,
        [string] $turnId,
        [int] $timeoutSeconds = 180) {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($timeoutSeconds)
    $latest = $null
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        Start-Sleep -Seconds 2
        $turns = Invoke-AgentJson GET "/api/conversations/$conversationId/turns" $null
        $matched = @($turns | Where-Object {
            $_.turnId -eq $turnId
        })
        if ($matched.Count -ne 1) {
            throw "会话轮次查询必须精确返回一条记录: turnId=$turnId count=$($matched.Count)"
        }
        $latest = $matched[0]
        if ($latest.status -eq "COMPLETED") {
            return $latest
        }
        if ($latest.status -eq "FAILED") {
            throw "真实会话轮次失败: turnId=$turnId error=$($latest.error)"
        }
    }
    $latestStatus = if ($null -eq $latest) { "NOT_FOUND" } else { $latest.status }
    throw "等待真实会话轮次超时: turnId=$turnId status=$latestStatus"
}

function Write-EvidenceJson([string] $fileName, $value) {
    $value | ConvertTo-Json -Depth 40 |
        Set-Content -LiteralPath (Join-Path $evidenceDirectory $fileName) -Encoding UTF8
}

$workspaceEvidence = Get-Content -Raw -LiteralPath $workspaceEvidencePath | ConvertFrom-Json
$workspaceId = $workspaceEvidence.workspaceId
if ([string]::IsNullOrWhiteSpace($workspaceId)) {
    throw "workspace.json 的 workspaceId 不能为空"
}

$readiness = Invoke-WebRequest `
    -Uri "http://localhost:8080/actuator/health/readiness" `
    -UseBasicParsing `
    -TimeoutSec 10
if ($readiness.StatusCode -ne 200) {
    throw "readiness 不是 200: $($readiness.StatusCode)"
}
$readinessContent = [System.Text.Encoding]::UTF8.GetString($readiness.Content)
$readinessBody = $readinessContent | ConvertFrom-Json
if ($readinessBody.status -ne "UP") {
    throw "readiness status 不是 UP: $($readinessBody.status)"
}

$firstFact = "新余高新区"
$secondFact = "电瓶车"
$conversation = Invoke-AgentJson POST "/api/workspaces/$workspaceId/conversations" @{}
$conversationId = $conversation.conversationId
if ([string]::IsNullOrWhiteSpace($conversationId)) {
    throw "创建会话未返回 conversationId"
}

$firstSubmitted = Invoke-AgentJson POST "/api/conversations/$conversationId/turns" @{
    content = "请记住：我明天从新余高新区独自出行，唯一交通工具是电瓶车。请用一句话确认这两个条件。"
}
$firstTurn = Wait-ConversationTurn $conversationId $firstSubmitted.turnId
if ([string]::IsNullOrWhiteSpace($firstTurn.assistantContent)) {
    throw "第一轮 assistantContent 不能为空"
}
foreach ($fact in @($firstFact, $secondFact)) {
    if (-not $firstTurn.assistantContent.Contains($fact)) {
        throw "第一轮回答缺少事实: $fact"
    }
}

$secondSubmitted = Invoke-AgentJson POST "/api/conversations/$conversationId/turns" @{
    content = "根据上一轮对话，先逐字复述我的出发地点和交通工具，再给出一个上午出行建议。"
}
$secondTurn = Wait-ConversationTurn $conversationId $secondSubmitted.turnId
if ([string]::IsNullOrWhiteSpace($secondTurn.assistantContent)) {
    throw "第二轮 assistantContent 不能为空"
}
foreach ($fact in @($firstFact, $secondFact)) {
    if (-not $secondTurn.assistantContent.Contains($fact)) {
        throw "第二轮回答未保留第一轮事实: $fact"
    }
}

$turns = Invoke-AgentJson GET "/api/conversations/$conversationId/turns" $null
if ($turns.Count -ne 2) {
    throw "会话必须精确包含两个轮次: count=$($turns.Count)"
}
if ($turns[0].turnIndex -ne 1 -or $turns[1].turnIndex -ne 2) {
    throw "会话轮次顺序错误: $($turns[0].turnIndex),$($turns[1].turnIndex)"
}
if ([string]::IsNullOrWhiteSpace($firstTurn.runId) `
        -or [string]::IsNullOrWhiteSpace($secondTurn.runId)) {
    throw "两轮 runId 均不能为空"
}
if ($firstTurn.runId -eq $secondTurn.runId) {
    throw "两轮必须使用独立 Run: runId=$($firstTurn.runId)"
}

$firstRun = Invoke-AgentJson GET "/api/runs/$($firstTurn.runId)" $null
$secondRun = Invoke-AgentJson GET "/api/runs/$($secondTurn.runId)" $null
if ($firstRun.status -ne "COMPLETED") {
    throw "第一轮 Run 未完成: $($firstRun.status)"
}
if ($secondRun.status -ne "COMPLETED") {
    throw "第二轮 Run 未完成: $($secondRun.status)"
}

Write-EvidenceJson "conversation-continuity-turns.json" $turns
Write-EvidenceJson "conversation-continuity-run-1.json" $firstRun
Write-EvidenceJson "conversation-continuity-run-2.json" $secondRun
Write-EvidenceJson "conversation-continuity.json" ([ordered]@{
    workspaceId = $workspaceId
    conversationId = $conversationId
    firstTurnId = $firstTurn.turnId
    firstRunId = $firstTurn.runId
    firstStatus = $firstTurn.status
    secondTurnId = $secondTurn.turnId
    secondRunId = $secondTurn.runId
    secondStatus = $secondTurn.status
    firstAnswerLength = $firstTurn.assistantContent.Length
    secondAnswerLength = $secondTurn.assistantContent.Length
    firstAnswerContainsFacts = $true
    secondAnswerContainsFacts = $true
})

if (-not (Test-Path -LiteralPath $auditLogPath)) {
    throw "审计日志不存在: $auditLogPath"
}
$auditLog = Get-Content -Raw -LiteralPath $auditLogPath
foreach ($auditFact in @(
        $conversationId,
        $firstTurn.turnId,
        $firstTurn.runId,
        $secondTurn.turnId,
        $secondTurn.runId,
        "CONVERSATION_TURN_SUBMITTED",
        "CONVERSATION_TURN_COMPLETED")) {
    if (-not $auditLog.Contains($auditFact)) {
        throw "审计日志缺少连续会话事实: $auditFact"
    }
}

Write-Output "conversationId=$conversationId"
Write-Output "firstTurnId=$($firstTurn.turnId) firstRunId=$($firstTurn.runId) status=$($firstTurn.status)"
Write-Output "secondTurnId=$($secondTurn.turnId) secondRunId=$($secondTurn.runId) status=$($secondTurn.status)"
Write-Output "evidence=$evidenceDirectory"
