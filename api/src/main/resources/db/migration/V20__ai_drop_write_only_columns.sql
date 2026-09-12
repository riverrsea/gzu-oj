-- V20: 删除 AI 运行的两个只写不读列
-- 这两列自写入起就没有任何读取方（Kotlin 无 SELECT、AiRunResponse 不返回、前端也无展示），
-- 与 V17 清理死列采用同一标准。
--
-- validation_evidence：差分证据快照。同样的信息可由 ai_sandbox_job.payload（源码与种子）
--   与 result_json（测试点、哈希、资源百分比）重新拼出，删列不丢可审计性。
-- human_correction：人工澄清内容的副本。Agent 实际是从 ai_agent_outbox 的 RESUME_RUN
--   事件 payload 取得澄清内容，而该事件行会保留，删列不丢信息。
ALTER TABLE ai_problem_run
    DROP COLUMN validation_evidence,
    DROP COLUMN human_correction;
