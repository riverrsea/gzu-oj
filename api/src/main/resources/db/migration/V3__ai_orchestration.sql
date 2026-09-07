ALTER TABLE ai_problem_run
    ADD COLUMN coordinator_lease UUID,
    ADD COLUMN coordinator_lease_expires_at TIMESTAMPTZ,
    ADD COLUMN next_run_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN publication_gate JSONB,
    ADD COLUMN validation_evidence JSONB;

CREATE INDEX ix_ai_problem_run_schedule
    ON ai_problem_run(next_run_at, created_at)
    WHERE state IN (
        'DRAFT', 'ANALYZING', 'GENERATING_SOLUTIONS', 'REVIEWING',
        'GENERATING_TESTS', 'VALIDATING'
    );

COMMENT ON COLUMN ai_problem_run.coordinator_lease IS 'API 内 AI 协调器的短期执行租约';
COMMENT ON COLUMN ai_problem_run.publication_gate IS '六项确定性发布门禁结果';
COMMENT ON COLUMN ai_problem_run.validation_evidence IS '差分任务、固定种子、哈希和资源余量证据';

ALTER TABLE ai_problem_step
    ADD COLUMN request_json JSONB,
    ADD COLUMN response_json JSONB,
    ADD COLUMN ordinal INTEGER NOT NULL DEFAULT 1 CHECK (ordinal > 0);

COMMENT ON COLUMN ai_problem_step.request_json IS '脱敏后的模型输入和上下文审计';
COMMENT ON COLUMN ai_problem_step.response_json IS 'Spring AI 结构化模型响应';
