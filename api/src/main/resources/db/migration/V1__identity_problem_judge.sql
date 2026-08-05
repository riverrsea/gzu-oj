CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE app_user (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(32) NOT NULL,
    email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_app_user_username_lower ON app_user (lower(username));
CREATE UNIQUE INDEX uk_app_user_email_lower ON app_user (lower(email));
COMMENT ON TABLE app_user IS '平台注册用户';
COMMENT ON COLUMN app_user.password_hash IS 'Argon2id 密码哈希';

CREATE TABLE email_verification (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    code_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_email_verification_user ON email_verification(user_id, expires_at DESC);
COMMENT ON TABLE email_verification IS '邮箱验证的一次性验证码';

CREATE TABLE password_reset (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE password_reset IS '找回密码的一次性令牌';

CREATE TABLE artifact (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    storage_key VARCHAR(512) NOT NULL UNIQUE,
    sha256 CHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    media_type VARCHAR(128) NOT NULL,
    created_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE artifact IS 'LocalArtifactStore 制品元数据';

CREATE TABLE problem (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_key VARCHAR(128) NOT NULL UNIQUE,
    created_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
    current_published_version_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE problem IS '跨版本稳定的题目标识';

CREATE TABLE problem_version (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    problem_id UUID NOT NULL REFERENCES problem(id) ON DELETE RESTRICT,
    version_number INTEGER NOT NULL CHECK (version_number > 0),
    title VARCHAR(200) NOT NULL,
    school VARCHAR(200) NOT NULL,
    year SMALLINT NOT NULL CHECK (year BETWEEN 1900 AND 2200),
    tags TEXT[] NOT NULL DEFAULT '{}',
    difficulty VARCHAR(16) NOT NULL CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD')),
    source_url TEXT,
    statement_markdown TEXT NOT NULL,
    time_limit_ms INTEGER NOT NULL CHECK (time_limit_ms BETWEEN 100 AND 60000),
    memory_limit_mib INTEGER NOT NULL CHECK (memory_limit_mib BETWEEN 16 AND 2048),
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED', 'WITHDRAWN')),
    data_notice VARCHAR(200),
    content_sha256 CHAR(64) NOT NULL,
    created_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    UNIQUE(problem_id, version_number)
);
ALTER TABLE problem ADD CONSTRAINT fk_problem_current_version
    FOREIGN KEY (current_published_version_id) REFERENCES problem_version(id) ON DELETE RESTRICT;
CREATE INDEX ix_problem_version_catalog ON problem_version(status, school, year, difficulty);
CREATE INDEX ix_problem_version_tags ON problem_version USING GIN(tags);
COMMENT ON TABLE problem_version IS '不可变的题目版本；发布后不允许原地修改';
COMMENT ON COLUMN problem_version.school IS '题目学校元数据，不设计学院字段';

CREATE TABLE problem_test_case (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    problem_version_id UUID NOT NULL REFERENCES problem_version(id) ON DELETE RESTRICT,
    ordinal INTEGER NOT NULL CHECK (ordinal > 0),
    score INTEGER NOT NULL CHECK (score BETWEEN 0 AND 100),
    input_artifact_id UUID NOT NULL REFERENCES artifact(id) ON DELETE RESTRICT,
    output_artifact_id UUID NOT NULL REFERENCES artifact(id) ON DELETE RESTRICT,
    sample BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(problem_version_id, ordinal)
);
COMMENT ON TABLE problem_test_case IS '题目版本的固定测试点';

CREATE TABLE submission (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    problem_version_id UUID NOT NULL REFERENCES problem_version(id) ON DELETE RESTRICT,
    language VARCHAR(16) NOT NULL CHECK (language IN ('C17', 'CPP17', 'JAVA21', 'PYTHON3')),
    source_code TEXT NOT NULL CHECK (octet_length(source_code) <= 131072),
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    score INTEGER NOT NULL DEFAULT 0 CHECK (score BETWEEN 0 AND 100),
    compile_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);
CREATE INDEX ix_submission_user_created ON submission(user_id, created_at DESC);
CREATE INDEX ix_submission_problem_user ON submission(problem_version_id, user_id, created_at DESC);
COMMENT ON TABLE submission IS '用户源码提交及最终脱敏结果';

CREATE TABLE submission_case_result (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    submission_id UUID NOT NULL REFERENCES submission(id) ON DELETE CASCADE,
    test_case_id UUID NOT NULL REFERENCES problem_test_case(id) ON DELETE RESTRICT,
    status VARCHAR(24) NOT NULL,
    score INTEGER NOT NULL CHECK (score BETWEEN 0 AND 100),
    time_ms BIGINT NOT NULL CHECK (time_ms >= 0),
    memory_kib BIGINT NOT NULL CHECK (memory_kib >= 0),
    message VARCHAR(500),
    UNIQUE(submission_id, test_case_id)
);
COMMENT ON TABLE submission_case_result IS '不含输入、标准输出或实际输出的测点结果';

CREATE TABLE idempotency_record (
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    submission_id UUID NOT NULL REFERENCES submission(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(user_id, idempotency_key)
);
COMMENT ON TABLE idempotency_record IS '提交接口 Idempotency-Key 记录';

CREATE TABLE worker_node (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL UNIQUE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    slots INTEGER NOT NULL CHECK (slots BETWEEN 1 AND 64),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    capabilities JSONB NOT NULL DEFAULT '{}',
    last_heartbeat_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE worker_node IS '异地判题节点和可撤销令牌哈希';

CREATE TABLE judge_job (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    submission_id UUID NOT NULL UNIQUE REFERENCES submission(id) ON DELETE CASCADE,
    priority INTEGER NOT NULL CHECK (priority BETWEEN 0 AND 1000),
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'LEASED', 'COMPLETED', 'CANCELED')),
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    infrastructure_attempts INTEGER NOT NULL DEFAULT 0 CHECK (infrastructure_attempts BETWEEN 0 AND 3),
    attempt_id UUID,
    lease_token_hash CHAR(64),
    leased_by UUID REFERENCES worker_node(id) ON DELETE SET NULL,
    lease_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CHECK ((status <> 'LEASED') OR
        (attempt_id IS NOT NULL AND lease_token_hash IS NOT NULL AND leased_by IS NOT NULL AND lease_expires_at IS NOT NULL))
);
CREATE INDEX ix_judge_job_claim ON judge_job(priority DESC, created_at)
    WHERE status IN ('QUEUED', 'LEASED');
COMMENT ON TABLE judge_job IS 'PostgreSQL 持久化判题队列的事实来源';
COMMENT ON COLUMN judge_job.lease_token_hash IS '租约明文令牌的 SHA-256';
