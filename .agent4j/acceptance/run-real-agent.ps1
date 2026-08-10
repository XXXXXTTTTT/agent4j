$ErrorActionPreference = "Stop"

$repoRoot = (& git -C $PSScriptRoot rev-parse --show-toplevel).Trim()
if ([string]::IsNullOrWhiteSpace($repoRoot)) {
    throw "无法定位 Git 仓库根目录"
}
$envFile = Join-Path $repoRoot ".env"
$fixture = Join-Path $PSScriptRoot "square-root-fix"
$evidence = Join-Path $PSScriptRoot "evidence"
New-Item -ItemType Directory -Force -Path $evidence | Out-Null

$mavenVersion = (& mvn -version 2>&1 | Out-String)
if ($mavenVersion -notmatch 'Java version:\s+21(?:\D|$)') {
    throw "验收夹具要求 Maven 使用 Java 21。当前 Maven 环境为:`n$mavenVersion"
}

$fixturePom = @'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>demo</groupId>
    <artifactId>square-root-fix</artifactId>
    <version>1.0.0</version>
    <properties>
        <maven.compiler.release>21</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.10.2</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
            </plugin>
        </plugins>
    </build>
</project>
'@
$fixtureSource = @'
package demo;

/** 将数字转换为平方根标签。 */
public final class NumberLabel {

    private NumberLabel() {
    }

    /** 当前实现故意存在错误，交给 Agent 通过真实任务修复。 */
    public static String label(double value) {
        return "sqrt=" + value;
    }
}
'@
$fixtureTest = @'
package demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NumberLabelTest {

    @Test
    void formatsSquareRootToTwoDecimals() {
        assertEquals("sqrt=2.00", NumberLabel.label(4.0));
    }
}
'@

function Set-Utf8WithoutBom([string] $path, [string] $content) {
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($path, $content, $encoding)
}

function Initialize-Fixture {
    New-Item -ItemType Directory -Force -Path (Join-Path $fixture "src\main\java\demo") | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $fixture "src\test\java\demo") | Out-Null
    Set-Utf8WithoutBom (Join-Path $fixture "pom.xml") $fixturePom
    Set-Utf8WithoutBom (Join-Path $fixture "src\main\java\demo\NumberLabel.java") $fixtureSource
    Set-Utf8WithoutBom (Join-Path $fixture "src\test\java\demo\NumberLabelTest.java") $fixtureTest
}

Initialize-Fixture

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
$requiredLlmValues = @(
    "AGENT_LLM_BASE_URL",
    "AGENT_LLM_API_KEY",
    "AGENT_LLM_CODE_MODEL",
    "AGENT_LLM_VISION_MODEL",
    "AGENT_LLM_QUICK_CLASSIFICATION_MODEL",
    "AGENT_LLM_FALLBACK_MODEL"
)
$missingLlmValues = @($requiredLlmValues | Where-Object {
    [string]::IsNullOrWhiteSpace((Get-EnvValue $_))
})
if ($missingLlmValues.Count -gt 0) {
    throw "以下 LLM 配置不能为空: $($missingLlmValues -join ', ')"
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
foreach ($requiredResponseText in @(
        "src/main/java/demo/NumberLabel.java",
        "mvn",
        "退出码：0")) {
    if (-not $final.assistantContent.Contains($requiredResponseText)) {
        throw "最终回答缺少事实证据: $requiredResponseText"
    }
}

if ($null -ne $final.runId) {
    $run = Invoke-Json GET "/api/runs/$($final.runId)" $null
    $run | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath (Join-Path $evidence "run-final.json")
    if ($run.status -ne "COMPLETED") { throw "Run 未完成: $($run.status)" }
    $variables = $run.state.variables
    if ($variables.'ops.exitCode' -ne "0") {
        throw "Ops 退出码不是 0: $($variables.'ops.exitCode')"
    }
    if ($variables.'ops.timedOut' -ne "false") { throw "Ops 不应超时" }
    if ($variables.'reviewer.approved' -ne "true") { throw "Reviewer 未批准" }
    if (-not $variables.'coder.updatedFiles'.Contains("src/main/java/demo/NumberLabel.java")) {
        throw "Run 状态缺少 NumberLabel.java 修改记录"
    }
    curl.exe --silent --show-error --max-time 15 "http://localhost:8080/api/runs/$($final.runId)/events" | Set-Content -LiteralPath (Join-Path $evidence "trace-sse.txt")
    curl.exe --silent --show-error --max-time 15 "http://localhost:8080/api/runs/$($final.runId)/logs" | Set-Content -LiteralPath (Join-Path $evidence "terminal-sse.txt")
    $trace = Get-Content -Raw (Join-Path $evidence "trace-sse.txt")
    foreach ($nodeName in @("planner", "coder", "ops", "reviewer")) {
        if (-not $trace.Contains($nodeName)) { throw "Trace SSE 缺少节点: $nodeName" }
    }
    $terminal = Get-Content -Raw (Join-Path $evidence "terminal-sse.txt")
    if (-not $terminal.Contains("BUILD SUCCESS")) { throw "终端 SSE 缺少 BUILD SUCCESS" }
    if (-not $terminal.Contains("Failures: 0")) { throw "终端 SSE 缺少测试通过结果" }
}

$importedFixture = Join-Path $repoRoot ".agent4j\imports\$workspaceId"
Push-Location $importedFixture
try {
    mvn test | Tee-Object -FilePath (Join-Path $evidence "fixture-maven-test.txt")
    if ($LASTEXITCODE -ne 0) { throw "Agent 修复后的 Maven 测试失败" }
} finally {
    Pop-Location
}

$auditLogPath = Join-Path $repoRoot "logs\agent4j-current.log"
if (-not (Test-Path -LiteralPath $auditLogPath)) { throw "审计日志不存在: $auditLogPath" }
$auditLog = Get-Content -Raw -LiteralPath $auditLogPath
foreach ($auditFact in @(
        "WORKSPACE_IMPORT_COMPLETED",
        "CONVERSATION_TURN_SUBMITTED",
        "CONVERSATION_TURN_STARTED",
        "CONVERSATION_TURN_COMPLETED",
        $workspaceId,
        $turnId,
        $final.runId)) {
    if (-not $auditLog.Contains($auditFact)) { throw "审计日志缺少事实: $auditFact" }
}

Write-Output "workspaceId=$workspaceId"
Write-Output "conversationId=$conversationId"
Write-Output "turnId=$turnId"
Write-Output "runId=$($final.runId)"
Write-Output "evidence=$evidence"
