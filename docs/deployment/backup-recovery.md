# PostgreSQL 备份与恢复演练

本项目把 PostgreSQL 作为 Checkpoint、会话、记忆和安全违规的权威数据源。备份文件、校验和以及 `.env` 永远保存在仓库外部，不提交到 Git。

## 1. 创建逻辑备份

在包含 `.env` 的部署目录执行：

```powershell
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backup = "agent4j-$stamp.dump"
docker compose -f docker-compose.yml --env-file .env exec -T postgres \
  pg_dump -U $env:POSTGRES_USER -d $env:POSTGRES_DB -Fc --no-owner --no-acl > $backup
Get-FileHash $backup -Algorithm SHA256 | Out-File "$backup.sha256"
```

备份命令必须返回退出码 `0`，并且生成非空 `.dump` 与 `.sha256` 文件。

## 2. 在一次性数据库中恢复

使用隔离的临时 PostgreSQL 实例，不覆盖线上卷：

```powershell
docker run --rm -d --name agent4j-restore-test `
  -e POSTGRES_DB=restore `
  -e POSTGRES_USER=restore `
  -e POSTGRES_PASSWORD=restore `
  -p 55432:5432 pgvector/pgvector:pg16
docker exec agent4j-restore-test pg_isready -U restore -d restore
Get-Content $backup -Raw -AsByteStream | docker exec -i agent4j-restore-test `
  pg_restore -U restore -d restore --no-owner --no-acl
```

恢复后执行只读校验：

```powershell
docker exec agent4j-restore-test psql -U restore -d restore -c `
  "select count(*) from agent_runs;"
docker exec agent4j-restore-test psql -U restore -d restore -c `
  "select version from flyway_schema_history order by installed_rank desc limit 1;"
docker rm -f agent4j-restore-test
```

必须确认 `flyway_schema_history` 存在且版本不低于当前迁移版本，随后再将恢复库接入隔离的应用实例做 readiness 检查。生产恢复前需停止写入、核对 SHA-256，并由具备权限的操作者执行审批记录。
