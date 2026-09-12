-- V19: AI 分阶段编译门禁
-- 每个生成节点产出源码后立即提交一次"只编译"的沙箱任务，避免全部生成完才发现编译错误。
ALTER TABLE ai_sandbox_job
    ADD COLUMN stage VARCHAR(16) NOT NULL DEFAULT 'DIFFERENTIAL'
        CHECK (stage IN ('DIFFERENTIAL', 'TEST_DATA', 'SOLUTIONS', 'BRUTE_FORCE')),
    ADD COLUMN attempt INTEGER NOT NULL DEFAULT 0 CHECK (attempt BETWEEN 0 AND 20);

COMMENT ON COLUMN ai_sandbox_job.stage IS '任务类型：DIFFERENTIAL 为全量差分，其余为对应产物的编译门禁';
COMMENT ON COLUMN ai_sandbox_job.attempt IS '同一修复轮次内该阶段的第几次尝试，用于编译门禁重试的幂等键';
COMMENT ON COLUMN ai_sandbox_job.payload IS '差分任务锁定的标程、暴力解、生成器、校验器和固定种子；编译门禁锁定待编译产物清单';

-- 一轮内每个阶段各有一次尝试，唯一键必须把阶段和尝试次数纳入。
ALTER TABLE ai_sandbox_job DROP CONSTRAINT uk_ai_sandbox_job_run_round;
ALTER TABLE ai_sandbox_job
    ADD CONSTRAINT uk_ai_sandbox_job_run_round_stage_attempt UNIQUE (run_id, repair_round, stage, attempt);
