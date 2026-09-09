-- V15: AI 录题人工接管与恢复
-- 记录正在等待人工 review 的失败阶段节点与人工澄清内容，赋能 NEEDS_REVIEW 恢复。
ALTER TABLE ai_problem_run
    ADD COLUMN resume_target VARCHAR(32),
    ADD COLUMN human_correction JSONB;

COMMENT ON COLUMN ai_problem_run.resume_target IS '等待人工接管时所在的阶段小状态：ANALYZING 或 REVIEWING；为空表示该运行不可恢复';
COMMENT ON COLUMN ai_problem_run.human_correction IS '人工 review 后回传给 Agent 重新执行节点时携带的结构化澄清内容';

-- 允许新增 RESUME_RUN outbox 事件类型：先按列匹配并删除旧的 CHECK 约束，再重建扩充集合。
-- 使用动态 SQL 而非硬编码约束名，避免约束自动命名在不同环境不一致导致迁移失败。
DO $$
DECLARE
    cname text;
BEGIN
    SELECT conname INTO cname
    FROM pg_constraint
    WHERE conrelid = 'ai_agent_outbox'::regclass
      AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%event_type%';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE ai_agent_outbox DROP CONSTRAINT %I', cname);
    END IF;
END $$;

ALTER TABLE ai_agent_outbox
    ADD CONSTRAINT ai_agent_outbox_event_type_check
    CHECK (event_type IN ('START_RUN', 'SANDBOX_RESULT', 'CANCEL_RUN', 'RESUME_RUN'));
