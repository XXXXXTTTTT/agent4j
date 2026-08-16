$ErrorActionPreference = "Stop"

$repoRoot = (& git -C $PSScriptRoot rev-parse --show-toplevel).Trim()
if ([string]::IsNullOrWhiteSpace($repoRoot)) { throw "无法定位 Git 仓库根目录" }
$envFile = Join-Path $repoRoot ".env"
if (-not (Test-Path -LiteralPath $envFile)) { throw ".env 不存在: $envFile" }

$envLines = Get-Content -LiteralPath $envFile -Encoding UTF8
function Get-EnvValue([string] $name) {
    $prefix = "$name="
    $matches = @($envLines | Where-Object { $_.StartsWith($prefix, [System.StringComparison]::Ordinal) })
    if ($matches.Count -ne 1) { throw "$name 必须在 .env 中精确配置一次" }
    return $matches[0].Substring($prefix.Length).Trim()
}
if ((Get-EnvValue "AGENT_LLM_ENABLED") -ne "true") { throw "AGENT_LLM_ENABLED 必须精确为 true" }
foreach ($name in @("AGENT_LLM_BASE_URL", "AGENT_LLM_API_KEY", "AGENT_LLM_CODE_MODEL", "AGENT_LLM_VISION_MODEL", "AGENT_LLM_QUICK_CLASSIFICATION_MODEL", "AGENT_LLM_FALLBACK_MODEL")) {
    if ([string]::IsNullOrWhiteSpace((Get-EnvValue $name))) { throw "$name 不能为空" }
}
$toolchainsFile = Join-Path $env:USERPROFILE ".m2\toolchains.xml"
if (-not (Test-Path -LiteralPath $toolchainsFile)) { throw "Java 21 Maven Toolchain 配置不存在: $toolchainsFile" }
$toolchains = [xml](Get-Content -Raw -LiteralPath $toolchainsFile)
$jdkHome = $toolchains.toolchains.toolchain.configuration.jdkHome | Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($jdkHome) -or -not (Test-Path -LiteralPath (Join-Path $jdkHome "bin\java.exe"))) { throw "toolchains.xml 未提供可用 Java 21 JDK" }
$env:JAVA_HOME = $jdkHome
$env:Path = "$(Join-Path $jdkHome 'bin');$env:Path"

$evidence = Join-Path $PSScriptRoot "evidence\workspace-development-loop"
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$directoryName = "workspace-edd-$([DateTimeOffset]::Now.ToUnixTimeMilliseconds())"
$hostProject = Join-Path $repoRoot $directoryName

function Invoke-Json([string] $method, [string] $uri, $body) {
    $params = @{ Method = $method; Uri = "http://localhost:8080$uri"; UseBasicParsing = $true }
    if ($null -ne $body) {
        $params.ContentType = "application/json"
        $params.Body = ($body | ConvertTo-Json -Depth 20 -Compress)
    }
    Invoke-RestMethod @params
}

$readiness = Invoke-WebRequest -Uri "http://localhost:8080/actuator/health/readiness" -UseBasicParsing
if ($readiness.StatusCode -ne 200) { throw "readiness 不是 200" }
$readiness.Content | Set-Content -LiteralPath (Join-Path $evidence "readiness.json")

$workspace = Invoke-Json POST "/api/workspaces/projects" @{ displayName = "Workspace EDD"; directoryName = $directoryName; repositoryId = $directoryName }
$workspace | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $evidence "workspace.json")
$workspaceId = $workspace.workspaceId
Start-Sleep -Seconds 2

$files = @{
    "pom.xml" = @'
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>demo</groupId><artifactId>workspace-edd</artifactId><version>1.0.0</version>
    <properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
    <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.10.2</version><scope>test</scope></dependency></dependencies>
    <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.2.5</version></plugin></plugins></build>
</project>
'@
    "src/main/java/demo/NumberLabel.java" = @'
package demo;
public final class NumberLabel {
    private NumberLabel() { }
    public static String label(double value) { return "sqrt=" + value; }
}
'@
    "src/test/java/demo/NumberLabelTest.java" = @'
package demo;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
class NumberLabelTest {
    @Test void formatsSquareRootToTwoDecimals() { assertEquals("sqrt=2.00", NumberLabel.label(4.0)); }
}
'@
}
foreach ($entry in $files.GetEnumerator()) {
    $result = Invoke-Json PUT "/api/workspaces/$workspaceId/files/content" @{ path = $entry.Key; content = $entry.Value; expectedSha256 = "" }
    if ($result.path -ne $entry.Key) { throw "文件写入响应路径不正确: $($entry.Key)" }
}

$rootListing = @(Invoke-Json GET "/api/workspaces/$workspaceId/files" $null)
if (-not ($rootListing | Where-Object { $_.path -eq "src" -and $_.kind -eq "DIRECTORY" })) {
    throw "文件树根目录缺少 src 目录"
}
$nestedListing = @(Invoke-Json GET "/api/workspaces/$workspaceId/files?path=src/main/java/demo" $null)
if (-not ($nestedListing | Where-Object { $_.path -eq "src/main/java/demo/NumberLabel.java" -and $_.kind -eq "FILE" })) {
    throw "文件树嵌套目录缺少 NumberLabel.java"
}
$initialSource = Invoke-Json GET "/api/workspaces/$workspaceId/files/content?path=src/main/java/demo/NumberLabel.java" $null
if ([string]::IsNullOrWhiteSpace($initialSource.sha256)) { throw "文件读取响应缺少 SHA-256" }
try {
    Invoke-Json PUT "/api/workspaces/$workspaceId/files/content" @{ path = "src/main/java/demo/NumberLabel.java"; content = $files["src/main/java/demo/NumberLabel.java"] + "`n"; expectedSha256 = "stale-sha" } | Out-Null
    throw "过期 SHA 写入没有被拒绝"
} catch {
    if ($_.Exception.Response -eq $null -or [int]$_.Exception.Response.StatusCode -ne 409) { throw }
}
$initialSource | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $evidence "initial-source.json")

New-Item -ItemType Directory -Force -Path $hostProject | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $hostProject "src\main\java\demo") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $hostProject "src\test\java\demo") | Out-Null
Push-Location $hostProject
try {
    mvn test | Tee-Object -FilePath (Join-Path $evidence "initial-test.txt")
    if ($LASTEXITCODE -eq 0) { throw "初始测试必须失败" }
} finally { Pop-Location }

$conversation = Invoke-Json POST "/api/workspaces/$workspaceId/conversations" @{}
$conversationId = $conversation.conversationId
$turn = Invoke-Json POST "/api/conversations/$conversationId/turns" @{ content = "修复 src/main/java/demo/NumberLabel.java 的实现，使 NumberLabel.label(4.0) 返回 sqrt=2.00，并运行 Maven 测试。最后说明修改文件和测试结果。" }
$turnId = $turn.turnId
$turn | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $evidence "turn-submitted.json")

for ($attempt = 1; $attempt -le 180; $attempt++) {
    Start-Sleep -Seconds 2
    $turns = Invoke-Json GET "/api/conversations/$conversationId/turns" $null
    $current = $turns | Where-Object { $_.turnId -eq $turnId }
    if ($null -ne $current) { $current | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $evidence "turn-final.json") }
    if ($null -ne $current -and $current.status -in @("COMPLETED", "FAILED")) { break }
}

$final = Get-Content -Raw (Join-Path $evidence "turn-final.json") | ConvertFrom-Json
if ($null -eq $final.runId) { throw "轮次缺少 runId" }
$run = Invoke-Json GET "/api/runs/$($final.runId)" $null
$run | ConvertTo-Json -Depth 50 | Set-Content -LiteralPath (Join-Path $evidence "run-final.json")
if ($final.status -ne "COMPLETED") { throw "真实 Agent 轮次未完成: $($final.status)" }
if ([string]::IsNullOrWhiteSpace($final.assistantContent)) { throw "final_response 为空" }
if ($run.status -ne "COMPLETED") { throw "Run 未完成: $($run.status)" }
if ($run.state.variables.'ops.exitCode' -ne "0") { throw "Ops 退出码不是 0" }
if ($run.state.variables.'reviewer.approved' -ne "true") { throw "Reviewer 未批准" }
if (-not $run.state.variables.'coder.updatedFiles'.Contains("src/main/java/demo/NumberLabel.java")) { throw "Run 缺少源码修改记录" }

$saved = Invoke-Json GET "/api/workspaces/$workspaceId/files/content?path=src/main/java/demo/NumberLabel.java" $null
$saved | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $evidence "saved-source.json")
if (-not $saved.content.Contains("sqrt=")) { throw "保存源码缺少 sqrt 实现" }
$traceSse = Join-Path $evidence "trace-sse.txt"
curl.exe --silent --show-error --max-time 20 "http://localhost:8080/api/runs/$($final.runId)/events" | Set-Content -LiteralPath $traceSse
$traceCurlExit = $LASTEXITCODE
$terminalSse = Join-Path $evidence "terminal-sse.txt"
curl.exe --silent --show-error --max-time 20 "http://localhost:8080/api/runs/$($final.runId)/logs" | Set-Content -LiteralPath $terminalSse
$terminalCurlExit = $LASTEXITCODE
$trace = Get-Content -Raw $traceSse
foreach ($node in @("planner", "coder", "ops", "reviewer")) { if (-not $trace.Contains($node)) { throw "Trace 缺少节点: $node" } }
$terminalFrames = @(
    Get-Content -LiteralPath $terminalSse | Where-Object { $_.StartsWith("data:", [System.StringComparison]::Ordinal) } |
        ForEach-Object { $_.Substring(5) | ConvertFrom-Json }
)
$terminalSnapshot = $terminalFrames |
    Where-Object { $_.kind -eq "SNAPSHOT" -and $null -ne $_.terminal } |
    Select-Object -First 1
if ($null -eq $terminalSnapshot) { throw "终端 SSE 缺少结构化快照" }
if ([int]$terminalSnapshot.terminal.exitCode -ne 0) {
    throw "终端 SSE 的退出码不是 0: $($terminalSnapshot.terminal.exitCode)"
}

Push-Location $hostProject
try {
    mvn test | Tee-Object -FilePath (Join-Path $evidence "final-maven-test.txt")
    if ($LASTEXITCODE -ne 0) { throw "文件 API 保存后的 Maven 测试失败" }
} finally { Pop-Location }

Write-Output "workspaceId=$workspaceId"
Write-Output "conversationId=$conversationId"
Write-Output "turnId=$turnId"
Write-Output "runId=$($final.runId)"
Write-Output "project=$hostProject"
Write-Output "evidence=$evidence"
