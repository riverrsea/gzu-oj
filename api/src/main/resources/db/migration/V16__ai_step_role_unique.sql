-- V16: 同一运行内每个角色只保留最新一次结构化返回。
-- 此前重跑（如人工接管恢复）会在 ai_problem_step 追加新行，导致前端刷新后仍看到旧结果、无法按角色切换展示。
-- 先按 (run_id, role) 去重，仅保留 ordinal 最大（最新）的一行，再建立唯一约束供后续 UPSERT 使用。
DELETE FROM ai_problem_step
WHERE id NOT IN (
    SELECT id FROM (
        SELECT id,
               row_number() OVER (PARTITION BY run_id, role ORDER BY ordinal DESC, id DESC) AS rn
        FROM ai_problem_step
    ) t
    WHERE t.rn = 1
);

ALTER TABLE ai_problem_step
    ADD CONSTRAINT uk_ai_problem_step_run_role UNIQUE (run_id, role);

COMMENT ON CONSTRAINT uk_ai_problem_step_run_role ON ai_problem_step IS
    '同一运行内每个角色只保留一条最新结构化返回，供人工接管恢复后替换旧结果';
