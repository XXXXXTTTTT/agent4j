[CmdletBinding()]
param(
    [string]$ComposeFile = "docker-compose.local.yml",
    [string]$EnvFile = ".env"
)

$ErrorActionPreference = "Stop"

function ConvertTo-SqlIdentifier {
    param([Parameter(Mandatory)][string]$Value)
    return '"' + $Value.Replace('"', '""') + '"'
}

function ConvertTo-SqlLiteral {
    param([Parameter(Mandatory)][string]$Value)
    return "'" + $Value.Replace("'", "''") + "'"
}

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$composePath = (Resolve-Path (Join-Path $repositoryRoot $ComposeFile)).Path
$envPath = (Resolve-Path (Join-Path $repositoryRoot $EnvFile)).Path

# 直接使用 Compose 解析后的 JSON，避免自行推断 .env 的引号和转义规则。
$configJson = docker compose -f $composePath --env-file $envPath config --format json
if ($LASTEXITCODE -ne 0) {
    throw "无法解析 Docker Compose 配置"
}
$config = $configJson | ConvertFrom-Json
$postgres = $config.services.postgres
if ($null -eq $postgres) {
    throw "Compose 配置中不存在 postgres 服务"
}

$database = [string]$postgres.environment.POSTGRES_DB
$username = [string]$postgres.environment.POSTGRES_USER
$password = [string]$postgres.environment.POSTGRES_PASSWORD
if ([string]::IsNullOrWhiteSpace($database) -or
    [string]::IsNullOrWhiteSpace($username) -or
    [string]::IsNullOrWhiteSpace($password)) {
    throw "POSTGRES_DB、POSTGRES_USER 和 POSTGRES_PASSWORD 必须为非空值"
}

$dataMount = $postgres.volumes | Where-Object { $_.target -eq "/var/lib/postgresql/data" }
if ($null -eq $dataMount -or $dataMount.type -ne "volume") {
    throw "postgres 服务必须使用命名卷挂载 /var/lib/postgresql/data"
}
$volumeDefinition = $config.volumes.PSObject.Properties[$dataMount.source].Value
$volumeName = [string]$volumeDefinition.name
if ([string]::IsNullOrWhiteSpace($volumeName)) {
    throw "无法从 Compose 配置中读取 PostgreSQL 数据卷名称"
}

$volumeInspection = docker volume inspect $volumeName 2>$null
if ($LASTEXITCODE -ne 0) {
    throw "PostgreSQL 数据卷不存在: $volumeName"
}

docker compose -f $composePath --env-file $envPath stop agent-web postgres
if ($LASTEXITCODE -ne 0) {
    throw "无法停止 agent-web 和 postgres 服务"
}

$databaseIdentifier = ConvertTo-SqlIdentifier $database
$usernameIdentifier = ConvertTo-SqlIdentifier $username
$usernameLiteral = ConvertTo-SqlLiteral $username
$passwordLiteral = ConvertTo-SqlLiteral $password

# 单用户模式不开放网络端口，可在旧凭据未知时安全修复登录角色。
$sql = @(
    "DO `$agent4j`$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = $usernameLiteral) THEN CREATE ROLE $usernameIdentifier LOGIN; END IF; END `$agent4j`$;"
    "ALTER ROLE $usernameIdentifier LOGIN PASSWORD $passwordLiteral;"
    "ALTER DATABASE $databaseIdentifier OWNER TO $usernameIdentifier;"
    "GRANT CONNECT ON DATABASE $databaseIdentifier TO $usernameIdentifier;"
    "GRANT USAGE, CREATE ON SCHEMA public TO $usernameIdentifier;"
    "GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO $usernameIdentifier;"
    "GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO $usernameIdentifier;"
    "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO $usernameIdentifier;"
    "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO $usernameIdentifier;"
    "DO `$agent4j`$ DECLARE item record; BEGIN FOR item IN SELECT n.nspname AS schema_name, c.relname AS relation_name, c.relkind FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relkind IN ('r', 'S') AND c.relowner <> (SELECT oid FROM pg_roles WHERE rolname = $usernameLiteral) LOOP IF item.relkind = 'S' THEN EXECUTE format('ALTER SEQUENCE %I.%I OWNER TO %I', item.schema_name, item.relation_name, $usernameLiteral); ELSE EXECUTE format('ALTER TABLE %I.%I OWNER TO %I', item.schema_name, item.relation_name, $usernameLiteral); END IF; END LOOP; END `$agent4j`$;"
    "ALTER SCHEMA public OWNER TO $usernameIdentifier;"
) -join "`n"

$sql | docker run --rm -i --user postgres `
    -v "${volumeName}:/var/lib/postgresql/data" `
    pgvector/pgvector:pg16 postgres --single -D /var/lib/postgresql/data $database
if ($LASTEXITCODE -ne 0) {
    throw "PostgreSQL 凭据修复失败"
}

docker compose -f $composePath --env-file $envPath up -d
if ($LASTEXITCODE -ne 0) {
    throw "凭据已修复，但 Compose 服务重新启动失败"
}

Write-Host "PostgreSQL 凭据已修复，数据卷未删除: $volumeName"
