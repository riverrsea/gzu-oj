-- 测试点不再携带分值：提交得分统一由"通过点数 / 总点数"在服务端派生，
-- 因此测试点权重列（problem_test_case.score）与逐点得分列（submission_case_result.score）
-- 都不再需要。submission.score 保留，它是派生出来的提交结果。
ALTER TABLE problem_test_case DROP COLUMN score;
ALTER TABLE submission_case_result DROP COLUMN score;

COMMENT ON TABLE problem_test_case IS '题目版本的固定测试点；不含分值，得分按通过的测试点数派生';
COMMENT ON TABLE submission_case_result IS '不含输入、标准输出、实际输出与分值的测点结果';
