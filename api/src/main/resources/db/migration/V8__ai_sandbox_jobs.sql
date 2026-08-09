ALTER TABLE worker_node
    ADD COLUMN ai_slots INTEGER NOT NULL DEFAULT 2 CHECK (ai_slots BETWEEN 0 AND 16);

COMMENT ON COLUMN worker_node.ai_slots IS '与普通提交槽隔离的 AI 生成和差分并发槽数';

ALTER TABLE ai_problem_run
    ADD COLUMN requested_test_case_count INTEGER NOT NULL DEFAULT 10
        CHECK (requested_test_case_count BETWEEN 1 AND 200);

COMMENT ON COLUMN ai_problem_run.requested_test_case_count IS '管理员启动 AI 录题时锁定的目标测试点数量';

CREATE TABLE ai_sandbox_job (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL UNIQUE REFERENCES ai_problem_run(id) ON DELETE CASCADE,
    payload JSONB NOT NULL,
    priority INTEGER NOT NULL DEFAULT 10 CHECK (priority BETWEEN 0 AND 1000),
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'LEASED', 'COMPLETED', 'FAILED', 'CANCELED')),
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    infrastructure_attempts INTEGER NOT NULL DEFAULT 0 CHECK (infrastructure_attempts BETWEEN 0 AND 3),
    attempt_id UUID,
    lease_token_hash CHAR(64),
    leased_by UUID REFERENCES worker_node(id) ON DELETE SET NULL,
    lease_expires_at TIMESTAMPTZ,
    result_json JSONB,
    failure_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CHECK ((status <> 'LEASED') OR
        (attempt_id IS NOT NULL AND lease_token_hash IS NOT NULL AND leased_by IS NOT NULL AND lease_expires_at IS NOT NULL))
);

CREATE INDEX ix_ai_sandbox_job_claim ON ai_sandbox_job(priority DESC, created_at)
    WHERE status IN ('QUEUED', 'LEASED');

COMMENT ON TABLE ai_sandbox_job IS '由独立 Worker AI 槽执行的持久化测试生成和差分任务';
COMMENT ON COLUMN ai_sandbox_job.payload IS '租约创建时锁定的标程、暴力解、生成器、校验器和固定种子';
COMMENT ON COLUMN ai_sandbox_job.result_json IS 'Worker 返回并由 API 复核的测试点与差分证据';
COMMENT ON COLUMN ai_sandbox_job.lease_token_hash IS 'AI 沙箱租约明文令牌的 SHA-256';

ALTER TABLE problem_test_case
    ADD COLUMN generated_by_ai_run_id UUID REFERENCES ai_problem_run(id) ON DELETE SET NULL,
    ADD COLUMN generation_seed BIGINT;

COMMENT ON COLUMN problem_test_case.generated_by_ai_run_id IS '生成该测试点的 AI 录题运行；人工测试点为空';
COMMENT ON COLUMN problem_test_case.generation_seed IS 'AI 生成器使用的固定随机种子；人工测试点为空';
