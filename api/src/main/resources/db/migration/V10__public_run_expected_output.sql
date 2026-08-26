ALTER TABLE submission_run_case
    ADD COLUMN expected_output_text TEXT NOT NULL DEFAULT ''
        CHECK (octet_length(expected_output_text) <= 262144);

COMMENT ON COLUMN submission_run_case.expected_output_text IS '公开运行用例对应的样例标准输出，由控制端提交并供 Worker 比较';
