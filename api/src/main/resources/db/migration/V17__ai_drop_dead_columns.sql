-- V17: 清理 AI 录题死列（Phase 1 轻量重构）
-- 以下列在现架构中只写不读或从未写入非空值，属于旧 Spring-AI 协调器/早期设计的遗留。
-- 说明：调度与协调已由 Python Agent + ai_agent_outbox 承担，故 coordinator_lease/next_run_at 不再需要。

-- ai_problem_run：调度与提供方信息已迁出运行表。
ALTER TABLE ai_problem_run
    DROP COLUMN provider_base_url,
    DROP COLUMN coordinator_lease,
    DROP COLUMN coordinator_lease_expires_at,
    DROP COLUMN next_run_at;

-- 旧调度器用于扫描待推进运行的索引，随 next_run_at 一并废弃。
DROP INDEX IF EXISTS ix_ai_problem_run_schedule;

-- ai_problem_step：仅保留审计真正需要的字段。
ALTER TABLE ai_problem_step
    DROP COLUMN request_json,
    DROP COLUMN structured_response,
    DROP COLUMN source_artifact_id,
    DROP COLUMN seed,
    DROP COLUMN log_artifact_id;
