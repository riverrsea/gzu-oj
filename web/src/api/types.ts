export type Role = "USER" | "ADMIN";
export type Difficulty = "EASY" | "MEDIUM" | "HARD";
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
  sourceKey: string;
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
  sourceKey: string;
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
