-- 一次性清库脚本：清空全部业务数据，只保留账号与基础设施状态。
--
-- 这是**手动执行**的维护脚本，故意放在 db/maintenance/ 而不是 db/migration/，
-- 因此部署时不会被 Flyway 自动执行。执行方式见 docs/operations.md。
--
-- 保留（都属于账号或基础设施，不属于业务数据）：
--   app_user、email_verification、password_reset、spring_session、
--   spring_session_attributes、worker_node、flyway_schema_history
--
-- 特别注意 worker_node **必须保留**：它的 token_hash 就是判题 Worker 的认证凭据，
-- 删掉之后正在运行的 Worker 会在下一次心跳拿到 401，而且无法自行重新注册
-- （明文令牌只在创建时返回一次）。
--
-- 注意：本脚本只清数据库行。ArtifactStore 在磁盘上的制品文件（题目测试数据、
--       AI 源码与日志、导入包）不会被删除，孤儿文件的磁盘清理需要另行处理。

BEGIN;

-- 1. 题目、提交、AI 运行、练习、比赛与套卷、导入批次一次性清空。
--    这里明确列出全部表而不用 CASCADE：万一漏了某张表，TRUNCATE 会直接因为外键报错，
--    而不是静默连带清掉未预期的数据。
TRUNCATE TABLE
    ai_agent_outbox,
    ai_problem_run,
    ai_problem_step,
    ai_run_log,
    ai_sandbox_job,
    contest,
    contest_participant,
    contest_problem,
    favorite_problem,
    idempotency_record,
    import_batch,
    import_item,
    judge_job,
    problem,
    problem_feedback,
    problem_reference_solution,
    problem_test_case,
    problem_version,
    submission,
    submission_case_result,
    submission_run_case,
    timed_paper,
    timed_paper_attempt,
    timed_paper_problem,
    wrong_problem;

-- 2. 清理已经没有任何引用的制品行（题目测试数据、导入包归档）。
--    当前 schema 中引用 artifact 的列只有 problem_test_case 与 import_batch 两处，
--    它们在第 1 步已经清空。这里仍用带守卫的 DELETE 而不是 TRUNCATE：
--    以后新增了引用 artifact 的表，未被引用的行才会被删，不会误删别的东西。
DELETE FROM artifact a
WHERE NOT EXISTS (
        SELECT 1 FROM problem_test_case tc
        WHERE tc.input_artifact_id = a.id OR tc.output_artifact_id = a.id
    )
  AND NOT EXISTS (
        SELECT 1 FROM import_batch b WHERE b.archive_artifact_id = a.id
    );

COMMIT;
