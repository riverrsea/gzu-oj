CREATE TABLE ai_problem_state_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL REFERENCES ai_problem_run(id) ON DELETE CASCADE,
    from_state VARCHAR(32),
    to_state VARCHAR(32) NOT NULL,
    major_state VARCHAR(32) NOT NULL,
    message VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_ai_problem_state_history_run
    ON ai_problem_state_history(run_id, created_at, id);

COMMENT ON TABLE ai_problem_state_history IS 'AI 录题小状态和聚合大状态的追加式变更历史';
