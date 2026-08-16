[CmdletBinding()]
param(
    [Parameter(Mandatory = $false)]
    [string]$WorkspaceRoot = (Join-Path $PSScriptRoot '..'),
    [Parameter(Mandatory = $false)]
    [int]$Port = 8090,
    [Parameter(Mandatory = $false)]
    [string]$Shell = 'powershell.exe'
)

$ErrorActionPreference = 'Stop'
$resolvedWorkspace = (Resolve-Path -LiteralPath $WorkspaceRoot).Path
if (-not (Test-Path -LiteralPath $resolvedWorkspace -PathType Container)) {
    throw "WorkspaceRoot 必须是现有目录: $WorkspaceRoot"
}
if ($Port -lt 1 -or $Port -gt 65535) {
    throw 'Port 必须在 1 到 65535 之间'
}
if ($env:OS -ne 'Windows_NT') {
    throw 'PowerShell PTY bridge 只能在 Windows 宿主机运行'
}
Get-Command node -ErrorAction Stop | Out-Null

Push-Location $PSScriptRoot
try {
    if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'node_modules') -PathType Container)) {
        npm ci
        if ($LASTEXITCODE -ne 0) { throw "npm ci 失败，退出码: $LASTEXITCODE" }
    }
    $env:AGENT_TERMINAL_WORKSPACE_ROOT = $resolvedWorkspace
    $env:AGENT_TERMINAL_CONTAINER_WORKSPACE_ROOT = '/agent-workspace'
    $env:AGENT_TERMINAL_BRIDGE_PORT = [string]$Port
    $env:AGENT_TERMINAL_SHELL = $Shell
    & node src/server.cjs
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
