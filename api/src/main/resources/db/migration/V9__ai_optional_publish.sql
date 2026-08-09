ALTER TABLE ai_problem_run
    ADD COLUMN auto_publish BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN requested_sample_count INTEGER NOT NULL DEFAULT 0
        CHECK (requested_sample_count BETWEEN 0 AND 200);

COMMENT ON COLUMN ai_problem_run.auto_publish IS 'AI 差分通过后是否自动发布题目版本';
COMMENT ON COLUMN ai_problem_run.requested_sample_count IS '自动发布时从生成测试点开头选择为公开样例的数量';
