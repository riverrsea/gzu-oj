-- 外部来源键只用于导入和跨版本归并；手工题目可以没有外部键。
ALTER TABLE problem RENAME COLUMN source_key TO external_key;
ALTER TABLE problem ALTER COLUMN external_key DROP NOT NULL;

-- 导入暂存记录也统一使用 external_key，旧批次数据原样保留。
ALTER TABLE import_item RENAME COLUMN source_key TO external_key;

-- 历史数据库可能已经存在同一道题多个 PUBLISHED 版本，保留最新版本为当前公开版本。
WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY problem_id
               ORDER BY published_at DESC NULLS LAST, version_number DESC
           ) AS rank
    FROM problem_version
    WHERE status = 'PUBLISHED'
)
UPDATE problem_version pv
SET status = 'WITHDRAWN'
FROM ranked r
WHERE pv.id = r.id AND r.rank > 1;

UPDATE problem p
SET current_published_version_id = (
    SELECT pv.id
    FROM problem_version pv
    WHERE pv.problem_id = p.id AND pv.status = 'PUBLISHED'
    ORDER BY pv.published_at DESC NULLS LAST, pv.version_number DESC
    LIMIT 1
);

-- 发布切换由事务完成，数据库约束保证不会出现两个公开版本。
CREATE UNIQUE INDEX ux_problem_one_published_version
    ON problem_version(problem_id)
    WHERE status = 'PUBLISHED';

COMMENT ON COLUMN problem.external_key IS '可选的外部题目标识，例如 noobdream:1006；手工题目为空';
