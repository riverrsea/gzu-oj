-- V18: 合并 ai_agent_event 与 ai_problem_state_history 为统一追加式日志 ai_run_log。
-- 目的：同一份“进度”此前由事件流(驱动状态推导)与状态变迁历史(时间线)两表分别承载，
-- 语义重叠。合并为一张日志后，时间线与状态推导同源，且去掉重复的 major_state 落库。
CREATE TABLE ai_run_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL REFERENCES ai_problem_run(id) ON DELETE CASCADE,
    -- Agent 事件的幂等键；Kotlin 内部状态变迁为 NULL。
    event_id UUID,
    -- PROGRESS：Agent 进度事件；TRANSITION：状态机变迁。
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('PROGRESS', 'TRANSITION')),
    stage VARCHAR(64),
    status VARCHAR(32),
    message VARCHAR(2000),
    from_state VARCHAR(32),
    to_state VARCHAR(32),
    repair_round INTEGER NOT NULL DEFAULT 0 CHECK (repair_round BETWEEN 0 AND 2),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_ai_run_log_event UNIQUE (event_id)
);
CREATE INDEX ix_ai_run_log_run ON ai_run_log(run_id, created_at, id);
COMMENT ON TABLE ai_run_log IS 'AI 运行的统一追加式日志：Agent 进度事件(PROGRESS)与状态变迁(TRANSITION)';

-- 迁移既有事件与状态历史。
INSERT INTO ai_run_log(id, run_id, event_id, kind, stage, status, message, repair_round, created_at)
SELECT gen_random_uuid(), run_id, event_id, 'PROGRESS', stage, status, message, repair_round, created_at
FROM ai_agent_event;

INSERT INTO ai_run_log(id, run_id, kind, message, from_state, to_state, created_at)
SELECT id, run_id, 'TRANSITION', message, from_state, to_state, created_at
FROM ai_problem_state_history;

DROP TABLE ai_agent_event;
DROP TABLE ai_problem_state_history;
