export type Role = "USER" | "ADMIN";
export type Difficulty = "EASY" | "MEDIUM" | "HARD";
export type ProblemVersionStatus = "DRAFT" | "PUBLISHED" | "WITHDRAWN";
export type JudgeLanguage = "C17" | "CPP17" | "JAVA21" | "PYTHON3";
export type JudgeExecutionMode = "SUBMIT" | "RUN";
export type JudgeStatus =
  | "QUEUED"
  | "COMPILING"
  | "JUDGING"
  | "AC"
  | "PARTIAL"
  | "WA"
  | "CE"
  | "TLE"
  | "MLE"
  | "RE"
  | "OLE"
  | "SYSTEM_ERROR"
  | "CANCELED";

export interface ApiErrorBody { code: string; message: string; timestamp: string }
export interface CurrentUser { id: string; username: string; role: Role }

export interface ProblemSummary {
  id: string;
  versionId: string;
  externalKey: string | null;
  title: string;
  school: string;
  year: number;
  tags: string[];
  difficulty: Difficulty;
}

export interface LanguageLimit {
  language: JudgeLanguage;
  timeLimitMs: number;
  memoryLimitMiB: number;
}

export interface PublicSample { ordinal: number; input: string; output: string }

export interface ProblemDetail extends ProblemSummary {
  versionNumber: number;
  sourceUrl: string | null;
  statementMarkdown: string;
  languageLimits: LanguageLimit[];
  samples: PublicSample[];
  dataNotice: string | null;
}

export interface SubmissionCase {
  ordinal: number;
  status: JudgeStatus;
  score: number;
  timeMs: number;
  memoryKiB: number;
  message: string | null;
  input: string | null;
  actualOutput: string | null;
}

export interface Submission {
  id: string;
  problemId: string;
  problemVersionId: string;
  executionMode: JudgeExecutionMode;
  language: JudgeLanguage;
  status: JudgeStatus;
  score: number;
  compileMessage: string | null;
  /** 单条提交详情返回的源码；历史列表和状态推送为空。 */
  sourceCode: string | null;
  createdAt: string;
  finishedAt: string | null;
  testCases: SubmissionCase[];
}

export interface UserProblemSummary {
  problemId: string;
  versionId: string;
  title: string;
  school: string;
  year: number;
  difficulty: Difficulty;
}

export interface WrongProblem {
  problem: UserProblemSummary;
  bestScore: number;
  firstWrongAt: string;
  lastWrongAt: string;
  solvedAt: string | null;
}

export type ContestVisibility = "PUBLIC" | "PASSWORD";
export type ContestPhase = "UPCOMING" | "RUNNING" | "FINISHED";

export interface LockedProblem {
  ordinal: number;
  problemId: string;
  versionId: string;
  title: string;
}

export interface ContestRank {
  rank: number;
  username: string;
  totalScore: number;
  elapsedSeconds: number;
  problemScores: Record<string, number>;
}

/** 训练赛列表接口返回的轻量元数据。 */
export interface ContestSummary {
  id: string;
  title: string;
  visibility: ContestVisibility;
  ownerUsername: string;
  createdAt: string;
  startsAt: string;
  endsAt: string;
  phase: ContestPhase;
  maxParticipants: number;
  participantCount: number;
  joined: boolean;
}

export interface Contest {
  id: string;
  title: string;
  visibility: ContestVisibility;
  ownerUsername: string;
  createdAt: string;
  startsAt: string;
  endsAt: string;
  phase: ContestPhase;
  maxParticipants: number;
  participantCount: number;
  joined: boolean;
  problems: LockedProblem[];
  myScores: Record<string, number> | null;
  ranking: ContestRank[] | null;
}

export interface TimedPaper {
  id: string;
  title: string;
  durationMinutes: number;
  problems: LockedProblem[];
}

/** 个人计时作答的后端持久化状态。 */
export type TimedAttemptStatus = "RUNNING" | "PAUSED" | "FINISHED";

export interface TimedAttempt {
  id: string;
  paper: TimedPaper;
  startedAt: string;
  expiresAt: string;
  finished: boolean;
  status: TimedAttemptStatus;
  remainingSeconds: number;
  scores: Record<string, number>;
  totalScore: number;
  maximumScore: number;
}

export interface ImportItem {
  externalKey: string;
  status: string;
  contentSha256: string | null;
  errors: string[];
  title: string | null;
  testCaseCount: number;
}

export interface ImportBatch {
  id: string;
  status: string;
  items: ImportItem[];
}

export interface CreatedProblemVersion {
  problemId: string;
  versionId: string;
  versionNumber: number;
  status: string;
}

/** 管理员题库中的题目版本摘要。 */
export interface AdminProblemSummary {
  /** 跨版本稳定的题目标识。 */
  problemId: string;
  /** 不可变题目版本标识。 */
  versionId: string;
  /** 可选的外部题目标识。 */
  externalKey: string | null;
  /** 题目标题。 */
  title: string;
  /** 学校名称。 */
  school: string;
  /** 真题年份。 */
  year: number;
  /** 题目标签。 */
  tags: string[];
  /** 题目难度。 */
  difficulty: Difficulty;
  /** 版本号。 */
  versionNumber: number;
  /** 版本状态。 */
  status: ProblemVersionStatus;
  /** 测试点数量。 */
  testCaseCount: number;
  /** 测试点分值总和。 */
  scoreSum: number;
  /** 公开样例数量。 */
  sampleCount: number;
  /** 创建时间。 */
  createdAt: string;
  /** 发布时间。 */
  publishedAt: string | null;
}

/** 管理员题库分页结果。 */
export interface AdminProblemPage {
  /** 当前页的题目版本。 */
  items: AdminProblemSummary[];
  /** 从零开始的页码。 */
  page: number;
  /** 每页条数。 */
  size: number;
  /** 符合筛选条件的总版本数。 */
  total: number;
}

export interface AdminTestCaseDetail {
  ordinal: number;
  input: string;
  output: string;
  score: number;
  sample: boolean;
}

export interface AdminProblemVersionDetail {
  problemId: string;
  versionId: string;
  versionNumber: number;
  externalKey: string | null;
  title: string;
  school: string;
  year: number;
  tags: string[];
  difficulty: Difficulty;
  sourceUrl: string | null;
  statementMarkdown: string;
  timeLimitMs: number;
  memoryLimitMiB: number;
  status: ProblemVersionStatus;
  contentSha256: string;
  activeAiRun: boolean;
  dataNotice: string | null;
  testCases: AdminTestCaseDetail[];
}

export type AiMajorState =
  | "DRAFT"
  | "ANALYZING"
  | "GENERATING_SOLUTIONS"
  | "REVIEWING"
  | "TESTS_GENERATING"
  | "VALIDATING"
  | "PASSING"
  | "PUBLISHED"
  | "NEEDS_REVIEW"
  | "FAILED"
  | "CANCELED";

export interface AiStateHistoryEntry {
  majorState: AiMajorState;
  state: string;
  message: string | null;
  createdAt: string;
}

export interface AiAgentResponse {
  summary: string;
  ambiguities: string[];
  sourceCode: string | null;
  generatorSource: string | null;
  validatorSource: string | null;
  testPlan: string[];
  seeds: number[];
  findings: string[];
}

export interface AiStepResponse {
  id: string;
  role: string;
  state: string;
  response: AiAgentResponse | null;
  rawResponse: string | null;
  costMicrounits: number;
  contentSha256: string | null;
  finishedAt: string | null;
  failureReason: string | null;
}

export interface AiRun {
  id: string;
  problemVersionId: string;
  state: string;
  majorState: AiMajorState;
  repairRound: number;
  /** 管理员启动流程时锁定的目标测试点数量。 */
  requestedTestCaseCount: number;
  /** 差分通过后是否自动发布。 */
  autoPublish: boolean;
  /** 自动发布时选择的公开样例数量。 */
  requestedSampleCount: number;
  model: string;
  promptVersion: string;
  costMicrounits: number;
  failureReason: string | null;
  /** 处于人工接管时可恢复的失败阶段小状态；为空表示不可恢复。 */
  resumeTarget: string | null;
  completedRoles: string[];
  steps: AiStepResponse[];
  history: AiStateHistoryEntry[];
  /** 已通过真实沙箱差分并写入题目版本的测试点。 */
  generatedTestCases: AiGeneratedTestCase[];
  /** 标准答案源码是否已通过哈希校验并保存到数据库。 */
  referenceSolutionSaved: boolean;
}

/** 管理员可见的 AI 生成测试点。 */
export interface AiGeneratedTestCase {
  /** 测试点顺序。 */
  ordinal: number;
  /** 生成器固定种子。 */
  seed: number;
  /** 已通过输入校验器的完整输入。 */
  input: string;
  /** 由差分通过标程计算的标准输出。 */
  output: string;
  /** 自动分配的测试点分值。 */
  score: number;
  /** 是否作为公开样例返回。 */
  sample: boolean;
}

/** 创建判题 Worker 后返回的节点凭据。Token 只在本次响应中返回。 */
export interface CreatedWorker {
  /** Worker 节点标识。 */
  id: string;
  /** Worker 节点名称。 */
  name: string;
  /** 只展示一次的 Worker Bearer Token。 */
  token: string;
  /** Worker 并发判题槽数量。 */
  slots: number;
  /** 与普通提交隔离的 AI 生成和差分槽数量。 */
  aiSlots: number;
}
