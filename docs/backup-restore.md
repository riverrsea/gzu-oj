# 备份与恢复

数据库和 LocalArtifactStore 是一个一致性单元，必须在同一维护窗口配套备份。脚本接受 libpq 连接串，不接受 JDBC URL。

```bash
export GZU_OJ_DB_URL='postgresql://gzu_oj:password@127.0.0.1/gzu_oj'
export GZU_OJ_ARTIFACT_ROOT=/srv/gzu-oj/artifacts
infra/scripts/backup.sh /srv/gzu-oj/backups
```

每个备份目录包含 `database.dump`、`artifacts.tar.gz`、`SHA256SUMS` 和清单。备份完成后复制到异地存储并定期做恢复演练。

恢复前停止 Caddy、API 和 Worker，创建空制品目录，并明确确认覆盖操作：

```bash
export GZU_OJ_RESTORE_CONFIRM=restore-database-and-artifacts
infra/scripts/restore.sh /srv/gzu-oj/backups/20260805T120000Z
```

恢复后抽查 Flyway 版本、题目版本与测试点引用、制品文件 SHA-256、提交历史和队列状态，再启动 API。最后启动 Worker，确认旧过期租约重新入队且没有重复结算。
