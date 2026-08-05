CREATE TABLE favorite_problem (
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    problem_id UUID NOT NULL REFERENCES problem(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(user_id, problem_id)
);
COMMENT ON TABLE favorite_problem IS '用户题目收藏';

CREATE TABLE wrong_problem (
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    problem_id UUID NOT NULL REFERENCES problem(id) ON DELETE CASCADE,
    first_wrong_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_wrong_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    solved_at TIMESTAMPTZ,
    best_score INTEGER NOT NULL DEFAULT 0 CHECK (best_score BETWEEN 0 AND 100),
    PRIMARY KEY(user_id, problem_id)
);
COMMENT ON TABLE wrong_problem IS '保留历史且可标记解决的错题本';

CREATE TABLE contest (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    title VARCHAR(120) NOT NULL,
    visibility VARCHAR(16) NOT NULL CHECK (visibility IN ('PUBLIC', 'PASSWORD')),
    password_hash VARCHAR(255),
    starts_at TIMESTAMPTZ NOT NULL,
    duration_minutes INTEGER NOT NULL CHECK (duration_minutes BETWEEN 15 AND 300),
    max_participants INTEGER NOT NULL DEFAULT 5 CHECK (max_participants BETWEEN 1 AND 100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((visibility = 'PUBLIC' AND password_hash IS NULL) OR
           (visibility = 'PASSWORD' AND password_hash IS NOT NULL))
);
COMMENT ON TABLE contest IS '公开或口令训练赛';

CREATE TABLE contest_problem (
    contest_id UUID NOT NULL REFERENCES contest(id) ON DELETE CASCADE,
    problem_version_id UUID NOT NULL REFERENCES problem_version(id) ON DELETE RESTRICT,
    ordinal INTEGER NOT NULL CHECK (ordinal > 0),
    PRIMARY KEY(contest_id, problem_version_id),
    UNIQUE(contest_id, ordinal)
);
COMMENT ON TABLE contest_problem IS '比赛锁定的不可变题目版本';

CREATE TABLE contest_participant (
    contest_id UUID NOT NULL REFERENCES contest(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(contest_id, user_id)
);
ALTER TABLE submission ADD COLUMN contest_id UUID REFERENCES contest(id) ON DELETE RESTRICT;

CREATE TABLE timed_paper (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    title VARCHAR(120) NOT NULL,
    duration_minutes INTEGER NOT NULL CHECK (duration_minutes BETWEEN 15 AND 300),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE timed_paper IS '个人计时套卷模板';

CREATE TABLE timed_paper_problem (
    timed_paper_id UUID NOT NULL REFERENCES timed_paper(id) ON DELETE CASCADE,
    problem_version_id UUID NOT NULL REFERENCES problem_version(id) ON DELETE RESTRICT,
    ordinal INTEGER NOT NULL CHECK (ordinal > 0),
    PRIMARY KEY(timed_paper_id, problem_version_id),
    UNIQUE(timed_paper_id, ordinal)
);

CREATE TABLE timed_paper_attempt (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    timed_paper_id UUID NOT NULL REFERENCES timed_paper(id) ON DELETE RESTRICT,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    share_token_hash CHAR(64) UNIQUE,
    share_revoked_at TIMESTAMPTZ
);
COMMENT ON TABLE timed_paper_attempt IS '首次进入后独立计时的私密套卷作答';
ALTER TABLE submission ADD COLUMN timed_paper_attempt_id UUID REFERENCES timed_paper_attempt(id) ON DELETE RESTRICT;

CREATE TABLE problem_feedback (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    problem_version_id UUID NOT NULL REFERENCES problem_version(id) ON DELETE RESTRICT,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    content TEXT NOT NULL CHECK (char_length(content) BETWEEN 1 AND 4000),
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED', 'REJECTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ
);
COMMENT ON TABLE problem_feedback IS '题目与 AI 数据问题反馈';

CREATE TABLE import_batch (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_by UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    archive_artifact_id UUID NOT NULL REFERENCES artifact(id) ON DELETE RESTRICT,
    status VARCHAR(20) NOT NULL CHECK (status IN ('STAGING', 'VALIDATED', 'IMPORTED', 'FAILED', 'CANCELED')),
    summary JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);
COMMENT ON TABLE import_batch IS '总 ZIP 批量导入暂存批次';

CREATE TABLE import_item (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id UUID NOT NULL REFERENCES import_batch(id) ON DELETE CASCADE,
    source_key VARCHAR(128) NOT NULL,
    content_sha256 CHAR(64),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'VALID', 'SKIPPED', 'IMPORTED', 'INVALID')),
    errors JSONB NOT NULL DEFAULT '[]',
    preview JSONB NOT NULL DEFAULT '{}',
    UNIQUE(batch_id, source_key)
);
COMMENT ON TABLE import_item IS '批量导入的逐题预览与校验结果';

CREATE TABLE ai_problem_run (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    problem_version_id UUID NOT NULL REFERENCES problem_version(id) ON DELETE RESTRICT,
    state VARCHAR(32) NOT NULL,
    repair_round INTEGER NOT NULL DEFAULT 0 CHECK (repair_round BETWEEN 0 AND 2),
    provider_base_url TEXT NOT NULL,
    model VARCHAR(128) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    cost_microunits BIGINT NOT NULL DEFAULT 0 CHECK (cost_microunits >= 0),
    failure_reason TEXT,
    created_by UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE ai_problem_run IS '可恢复、可审计的 AI 录题状态机实例';

CREATE TABLE ai_problem_step (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL REFERENCES ai_problem_run(id) ON DELETE CASCADE,
    role VARCHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL,
    model VARCHAR(128) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    structured_response JSONB,
    source_artifact_id UUID REFERENCES artifact(id) ON DELETE RESTRICT,
    seed BIGINT,
    content_sha256 CHAR(64),
    log_artifact_id UUID REFERENCES artifact(id) ON DELETE RESTRICT,
    cost_microunits BIGINT NOT NULL DEFAULT 0 CHECK (cost_microunits >= 0),
    failure_reason TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);
COMMENT ON TABLE ai_problem_step IS 'AI 各角色调用、源码、种子、日志和费用审计';

CREATE TABLE spring_session (
    primary_id CHAR(36) NOT NULL PRIMARY KEY,
    session_id CHAR(36) NOT NULL UNIQUE,
    creation_time BIGINT NOT NULL,
    last_access_time BIGINT NOT NULL,
    max_inactive_interval INTEGER NOT NULL,
    expiry_time BIGINT NOT NULL,
    principal_name VARCHAR(100)
);
CREATE INDEX spring_session_ix2 ON spring_session(expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session(principal_name);
COMMENT ON TABLE spring_session IS 'Spring Security 服务端会话';

CREATE TABLE spring_session_attributes (
    session_primary_id CHAR(36) NOT NULL REFERENCES spring_session(primary_id) ON DELETE CASCADE,
    attribute_name VARCHAR(200) NOT NULL,
    attribute_bytes BYTEA NOT NULL,
    PRIMARY KEY (session_primary_id, attribute_name)
);
COMMENT ON TABLE spring_session_attributes IS '服务端会话属性';
