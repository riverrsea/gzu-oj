ALTER TABLE ai_sandbox_job DROP CONSTRAINT ai_sandbox_job_run_id_key;
ALTER TABLE ai_sandbox_job
    ADD COLUMN repair_round INTEGER NOT NULL DEFAULT 0 CHECK (repair_round BETWEEN 0 AND 2),
    ADD CONSTRAINT uk_ai_sandbox_job_run_round UNIQUE (run_id, repair_round);

COMMENT ON COLUMN ai_sandbox_job.repair_round IS 'Python Agent 发起的定向修复轮次，首轮为 0';

CREATE TABLE problem_reference_solution (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    problem_version_id UUID NOT NULL REFERENCES problem_version(id) ON DELETE RESTRICT,
    language VARCHAR(16) NOT NULL CHECK (language = 'CPP17'),
    source_code TEXT NOT NULL CHECK (octet_length(source_code) <= 131072),
    source_sha256 CHAR(64) NOT NULL,
    generated_by_run_id UUID NOT NULL REFERENCES ai_problem_run(id) ON DELETE RESTRICT,
    sandbox_job_id UUID NOT NULL REFERENCES ai_sandbox_job(id) ON DELETE RESTRICT,
    selected_candidate CHAR(1) NOT NULL CHECK (selected_candidate IN ('A', 'B')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_problem_reference_solution_version UNIQUE (problem_version_id),
    CONSTRAINT ck_problem_reference_solution_sha256
        CHECK (source_sha256 = encode(digest(convert_to(source_code, 'UTF8'), 'sha256'), 'hex'))
);

COMMENT ON TABLE problem_reference_solution IS '通过 Worker 沙箱验证的当前标准答案源码';
COMMENT ON COLUMN problem_reference_solution.source_code IS '直接保存在数据库中的 GNU C++17 源码，禁止写入制品存储';
COMMENT ON COLUMN problem_reference_solution.selected_candidate IS '按最坏 CPU、最坏内存、最后 A 的顺序选择的候选';

CREATE TABLE ai_agent_event (
    event_id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES ai_problem_run(id) ON DELETE CASCADE,
    stage VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    message VARCHAR(2000) NOT NULL,
    repair_round INTEGER NOT NULL CHECK (repair_round BETWEEN 0 AND 2),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_ai_agent_event_run ON ai_agent_event(run_id, created_at, event_id);
COMMENT ON TABLE ai_agent_event IS 'Python Agent 回传的模型无关、幂等进度事件';

CREATE TABLE ai_agent_outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(200) NOT NULL UNIQUE,
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN ('START_RUN', 'SANDBOX_RESULT', 'CANCEL_RUN')),
    run_id UUID NOT NULL REFERENCES ai_problem_run(id) ON DELETE CASCADE,
    payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 20),
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ
);
CREATE INDEX ix_ai_agent_outbox_dispatch ON ai_agent_outbox(available_at, created_at)
    WHERE status = 'PENDING';
COMMENT ON TABLE ai_agent_outbox IS 'Kotlin 向 Python Agent 派发启动、结果和取消通知的持久化 outbox';
