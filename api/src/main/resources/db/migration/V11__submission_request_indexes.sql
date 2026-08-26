-- 为服务端防抖按用户和请求指纹查找最近提交建立索引。
CREATE INDEX ix_idempotency_record_user_hash_created
    ON idempotency_record(user_id, request_hash, created_at DESC);

COMMENT ON INDEX ix_idempotency_record_user_hash_created
    IS '服务端运行/提交防抖：按用户和请求指纹查找最近幂等记录';
