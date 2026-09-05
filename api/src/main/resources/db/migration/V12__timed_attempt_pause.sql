ALTER TABLE timed_paper_attempt
    ADD COLUMN paused_at TIMESTAMPTZ,
    ADD COLUMN accumulated_paused_ms BIGINT NOT NULL DEFAULT 0
        CHECK (accumulated_paused_ms >= 0);

COMMENT ON COLUMN timed_paper_attempt.paused_at IS '当前暂停开始时间；非暂停状态为空';
COMMENT ON COLUMN timed_paper_attempt.accumulated_paused_ms IS '已经完成的暂停累计时长，单位毫秒';
