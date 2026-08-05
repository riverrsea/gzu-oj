ALTER TABLE submission
    ADD COLUMN execution_mode VARCHAR(16) NOT NULL DEFAULT 'SUBMIT'
        CHECK (execution_mode IN ('SUBMIT', 'RUN'));

COMMENT ON COLUMN submission.execution_mode IS 'SUBMIT 为正式判题，RUN 为用户可编辑公开输入运行';

CREATE TABLE submission_run_case (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    submission_id UUID NOT NULL REFERENCES submission(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 1 AND 8),
    input_text TEXT NOT NULL CHECK (octet_length(input_text) <= 262144),
    actual_output TEXT,
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    time_ms BIGINT NOT NULL DEFAULT 0 CHECK (time_ms >= 0),
    memory_kib BIGINT NOT NULL DEFAULT 0 CHECK (memory_kib >= 0),
    message VARCHAR(500),
    UNIQUE(submission_id, ordinal)
);

COMMENT ON TABLE submission_run_case IS '公开运行用例；只保存用户主动提供的输入和对应实际输出';
COMMENT ON COLUMN submission_run_case.actual_output IS '沙箱实际标准输出，上限由 Worker 的输出限制控制';

CREATE INDEX ix_submission_mode_user_created
    ON submission(user_id, execution_mode, created_at DESC);
