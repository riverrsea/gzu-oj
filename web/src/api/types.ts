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
}

export interface Contest {
  id: string;
  title: string;
  visibility: ContestVisibility;
  ownerUsername: string;
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

export interface TimedAttempt {
  id: string;
  paper: TimedPaper;
  startedAt: string;
  expiresAt: string;
  finished: boolean;
  scores: Record<string, number>;
  totalScore: number;
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

export interface AiRun {
  id: string;
  problemVersionId: string;
  state: string;
  repairRound: number;
  model: string;
  promptVersion: string;
  costMicrounits: number;
  failureReason: string | null;
  completedRoles: string[];
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
}
