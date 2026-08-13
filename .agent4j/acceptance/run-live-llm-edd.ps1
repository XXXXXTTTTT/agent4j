$ErrorActionPreference = "Stop"

$repoRoot = (& git -C $PSScriptRoot rev-parse --show-toplevel).Trim()
if ([string]::IsNullOrWhiteSpace($repoRoot)) {
    throw "无法定位 Git 仓库根目录"
}

$envFile = Join-Path $repoRoot ".env"
if (-not (Test-Path -LiteralPath $envFile)) {
    throw ".env 不存在: $envFile"
}

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

$requiredNames = @(
    "AGENT_LLM_ENABLED",
    "AGENT_LLM_BASE_URL",
    "AGENT_LLM_API_KEY",
    "AGENT_LLM_CODE_MODEL",
    "AGENT_LLM_VISION_MODEL",
    "AGENT_LLM_QUICK_CLASSIFICATION_MODEL",
    "AGENT_LLM_FALLBACK_MODEL"
)
foreach ($name in $requiredNames) {
    $value = Get-EnvValue $name
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "$name 不能为空"
    }
    [Environment]::SetEnvironmentVariable($name, $value, "Process")
}
if ($env:AGENT_LLM_ENABLED -ne "true") {
    throw "AGENT_LLM_ENABLED 必须精确为 true"
}

$mavenVersion = (& mvn -version 2>&1 | Out-String)
if ($mavenVersion -notmatch 'Java version:\s+21(?:\D|$)') {
    throw "真实 LLM EDD 要求 Maven 使用 Java 21。当前 Maven 环境为:`n$mavenVersion"
}

& mvn -pl agent-eval -am '-Dgroups=edd' '-Dtest=LlmEddTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
if ($LASTEXITCODE -ne 0) {
    throw "真实 LLM EDD 失败: exitCode=$LASTEXITCODE"
}

$reportDirectory = Join-Path $repoRoot "agent-eval\target\edd"
$report = Get-ChildItem -LiteralPath $reportDirectory -Filter "llm-edd-*.json" -File |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if ($null -eq $report) {
    throw "真实 LLM EDD 未生成报告"
}
$summary = Get-Content -Raw -LiteralPath $report.FullName | ConvertFrom-Json
if ($summary.transport -ne "live-openai-compatible") {
    throw "EDD transport 不正确: $($summary.transport)"
}
if ($summary.modelCallAttempts -le 0) {
    throw "真实 LLM EDD 未发起模型调用"
}
if (@($summary.scenarios | Where-Object { -not $_.passed }).Count -ne 0) {
    throw "真实 LLM EDD 存在失败场景"
}

Write-Output "report=$($report.FullName)"
Write-Output "modelCallAttempts=$($summary.modelCallAttempts)"
