-- 每位用户对同一套卷只有一次首次进入作答，保证重复请求不会重置计时。
ALTER TABLE timed_paper_attempt
    ADD CONSTRAINT uk_timed_paper_attempt_user UNIQUE (timed_paper_id, user_id);

COMMENT ON CONSTRAINT uk_timed_paper_attempt_user ON timed_paper_attempt
    IS '同一用户首次进入同一套卷时复用原作答，避免重复开始计时';
