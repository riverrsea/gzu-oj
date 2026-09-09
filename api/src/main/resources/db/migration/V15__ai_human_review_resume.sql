-- V15: AI 录题人工接管与恢复
-- 记录正在等待人工 review 的失败阶段节点与人工澄清内容，赋能 NEEDS_REVIEW 恢复。
ALTER TABLE ai_problem_run
    ADD COLUMN resume_target VARCHAR(32),
    ADD COLUMN human_correction JSONB;

COMMENT ON COLUMN ai_problem_run.resume_target IS '等待人工接管时所在的阶段小状态：ANALYZING 或 REVIEWING；为空表示该运行不可恢复';
COMMENT ON COLUMN ai_problem_run.human_correction IS '人工 review 后回传给 Agent 重新执行节点时携带的结构化澄清内容';

-- 扩充 outbox 事件类型：先删除 V13 内联生成的默认 CHECK，再重建加入 RESUME_RUN。
ALTER TABLE ai_agent_outbox DROP CONSTRAINT ai_agent_outbox_event_type_check;
ALTER TABLE ai_agent_outbox
    ADD CONSTRAINT ai_agent_outbox_event_type_check
    CHECK (event_type IN ('START_RUN', 'SANDBOX_RESULT', 'CANCEL_RUN', 'RESUME_RUN'));
