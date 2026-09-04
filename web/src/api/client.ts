import type {
  ApiErrorBody,
  AiRun,
  AdminProblemPage,
  AdminProblemVersionDetail,
  Contest,
  ContestRank,
  ContestSummary,
  ContestVisibility,
  CreatedProblemVersion,
  CreatedWorker,
  CurrentUser,
  Difficulty,
  ImportBatch,
  JudgeLanguage,
  ProblemDetail,
  ProblemVersionStatus,
  ProblemSummary,
  Submission,
  TimedAttempt,
  TimedPaper,
  UserProblemSummary,
  WrongProblem,
} from "./types";

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

let csrfToken: string | null = null;

/** 清除当前浏览器会话中缓存的 CSRF 令牌。注销后服务端会同时清除对应 Cookie。 */
function clearCsrfToken(): void {
  csrfToken = null;
}

async function ensureCsrf(): Promise<string> {
  if (csrfToken) return csrfToken;
  const response = await fetch("/api/v1/csrf", { credentials: "same-origin" });
  if (!response.ok) throw new ApiError(response.status, "CSRF_UNAVAILABLE", "安全令牌加载失败");
  const body = (await response.json()) as { token: string };
  csrfToken = body.token;
  return csrfToken;
}

/** 发起 API 请求；CSRF 令牌失效时只对变更请求自动刷新并重试一次。 */
async function request<T>(path: string, init: RequestInit = {}, retryAfterCsrf = true, timeoutMs?: number): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body && !(init.body instanceof FormData)) headers.set("Content-Type", "application/json");
  const method = (init.method ?? "GET").toUpperCase();
  const changesState = !["GET", "HEAD", "OPTIONS"].includes(method);
  if (changesState) {
    headers.set("X-XSRF-TOKEN", await ensureCsrf());
  }
  // 仅为显式要求超时的短请求创建控制器，避免中断 ZIP 导入等长耗时请求。
  const controller = timeoutMs ? new AbortController() : undefined;
  const timeoutId = timeoutMs ? window.setTimeout(() => controller?.abort(), timeoutMs) : undefined;
  let response: Response;
  try {
    response = await fetch(path, { ...init, headers, credentials: "same-origin", signal: controller?.signal ?? init.signal });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") {
      throw new ApiError(0, "REQUEST_TIMEOUT", "控制端响应超时，请确认 API 服务已经启动");
    }
    throw new ApiError(0, "NETWORK_ERROR", "无法连接控制端，请检查 API 地址和服务状态");
  } finally {
    if (timeoutId !== undefined) window.clearTimeout(timeoutId);
  }
  if (!response.ok) {
    const error = (await response.json().catch(() => null)) as ApiErrorBody | null;
    // Spring Security 的注销处理器会清除 CSRF Cookie；缓存令牌会导致下一次登录 403。
    if (response.status === 403 && changesState && retryAfterCsrf) {
      clearCsrfToken();
      return request(path, init, false);
    }
    throw new ApiError(response.status, error?.code ?? "REQUEST_FAILED", error?.message ?? "请求失败");
  }
  if (response.status === 204) return undefined as T;
  // Kotlin 控制器返回 Unit 时可能没有响应体；成功请求不应因此在前端解析阶段失败。
  const payload = await response.text();
  return (payload ? JSON.parse(payload) : undefined) as T;
}

export const api = {
  me: () => request<CurrentUser>("/api/v1/auth/me"),
  /** 使用用户名或邮箱、密码及图形验证码创建登录会话。 */
  login: (body: { identity: string; password: string; captcha: string }) =>
    request<CurrentUser>("/api/v1/auth/login", { method: "POST", body: JSON.stringify(body) }),
  /** 创建账号并向注册邮箱发送验证码。 */
  register: (body: { username: string; email: string; password: string; captcha: string }) =>
    request<{ message: string }>("/api/v1/auth/register", { method: "POST", body: JSON.stringify(body) }),
  /** 完成注册邮箱验证。 */
  verifyEmail: (body: { email: string; code: string }) =>
    request<{ message: string }>("/api/v1/auth/verify-email", { method: "POST", body: JSON.stringify(body) }),
  /** 校验图形验证码后发送密码重置邮件。 */
  requestPasswordReset: (body: { email: string; captcha: string }) =>
    request<{ message: string }>("/api/v1/auth/password-reset/request", { method: "POST", body: JSON.stringify(body) }),
  /** 使用邮件中的一次性令牌设置新密码。 */
  confirmPasswordReset: (body: { token: string; newPassword: string }) =>
    request<{ message: string }>("/api/v1/auth/password-reset/confirm", { method: "POST", body: JSON.stringify(body) }),
  /** 注销后清除页面级 CSRF 缓存，确保下一次登录重新获取令牌。 */
  logout: async () => {
    try {
      return await request<void>("/api/v1/auth/logout", { method: "POST" });
    } finally {
      clearCsrfToken();
    }
  },
  problems: (filters: { school?: string; year?: number; tag?: string; difficulty?: Difficulty }) => {
    const query = new URLSearchParams();
    Object.entries(filters).forEach(([key, value]) => {
      if (value !== undefined && value !== "") query.set(key, String(value));
    });
    return request<ProblemSummary[]>("/api/v1/problems?" + query.toString());
  },
  /** 查询管理员可见的全部题目版本，包括草稿和历史版本。 */
  adminProblems: (filters: {
    keyword?: string;
    school?: string;
    year?: number;
    tag?: string;
    difficulty?: Difficulty;
    status?: ProblemVersionStatus;
    page?: number;
    size?: number;
  }) => {
    const query = new URLSearchParams();
    Object.entries(filters).forEach(([key, value]) => {
      if (value !== undefined && value !== "") query.set(key, String(value));
    });
    return request<AdminProblemPage>("/api/v1/admin/problems?" + query.toString());
  },
  adminProblemVersion: (versionId: string) => request<AdminProblemVersionDetail>("/api/v1/admin/problems/versions/" + versionId),
  problem: (id: string) => request<ProblemDetail>("/api/v1/problems/" + id, {}, true, 15_000),
  problemVersion: (id: string) => request<ProblemDetail>("/api/v1/problems/versions/" + id, {}, true, 15_000),
  submit: (body: { problemId: string; problemVersionId: string; language: JudgeLanguage; sourceCode: string; contestId?: string; timedPaperAttemptId?: string }) =>
    request<Submission>("/api/v1/submissions", {
      method: "POST",
      headers: { "Idempotency-Key": crypto.randomUUID() },
      body: JSON.stringify(body),
    }),
  run: (body: { problemId: string; problemVersionId: string; language: JudgeLanguage; sourceCode: string; inputs: string[]; expectedOutputs: string[] }) =>
    request<Submission>("/api/v1/runs", {
      method: "POST",
      headers: { "Idempotency-Key": crypto.randomUUID() },
      body: JSON.stringify(body),
    }),
  submission: (id: string) => request<Submission>("/api/v1/submissions/" + id),
  /** 分页读取当前用户的正式提交历史；before 使用上一页最早提交时间作为游标。 */
  submissions: (params: { limit?: number; before?: string } = {}) => {
    const query = new URLSearchParams();
    if (params.limit !== undefined) query.set("limit", String(params.limit));
    if (params.before) query.set("before", params.before);
    return request<Submission[]>("/api/v1/submissions?" + query.toString());
  },
  favorite: (problemId: string) => request<void>("/api/v1/favorites/" + problemId, { method: "POST" }),
  unfavorite: (problemId: string) => request<void>("/api/v1/favorites/" + problemId, { method: "DELETE" }),
  favorites: () => request<UserProblemSummary[]>("/api/v1/favorites"),
  /** 查询当前用户曾经正式通过的题目 ID，跨题目版本合并。 */
  solvedProblemIds: () => request<string[]>("/api/v1/solved-problems"),
  wrongProblems: () => request<WrongProblem[]>("/api/v1/wrong-problems"),
  addWrongProblem: (problemId: string, submissionId: string) => request<void>("/api/v1/wrong-problems/" + problemId, {
    method: "POST",
    body: JSON.stringify({ submissionId }),
  }),
  contests: () => request<ContestSummary[]>("/api/v1/contests"),
  contest: (id: string) => request<Contest>("/api/v1/contests/" + id),
  contestRanking: (id: string) => request<ContestRank[]>("/api/v1/contests/" + id + "/ranking"),
  createContest: (body: { title: string; visibility: ContestVisibility; password?: string; startsAt: string; durationMinutes: number; problemIds: string[] }) =>
    request<Contest>("/api/v1/contests", { method: "POST", body: JSON.stringify(body) }),
  joinContest: (id: string, password?: string) =>
    request<Contest>("/api/v1/contests/" + id + "/participants", { method: "POST", body: JSON.stringify({ password }) }),
  timedPapers: () => request<TimedPaper[]>("/api/v1/timed-papers"),
  createTimedPaper: (body: { title: string; durationMinutes: number; problemIds: string[] }) =>
    request<TimedPaper>("/api/v1/timed-papers", { method: "POST", body: JSON.stringify(body) }),
  /** 查询当前用户已经开始过的套卷作答，用于恢复独立计时。 */
  timedAttempts: () => request<TimedAttempt[]>("/api/v1/timed-papers/attempts"),
  startTimedPaper: (id: string) => request<TimedAttempt>("/api/v1/timed-papers/" + id + "/attempts", { method: "POST" }),
  timedAttempt: (id: string) => request<TimedAttempt>("/api/v1/timed-papers/attempts/" + id),
  shareTimedAttempt: (id: string) => request<{ token: string }>("/api/v1/timed-papers/attempts/" + id + "/share", { method: "POST" }),
  sharedTimedAttempt: (token: string) => request<TimedAttempt>("/api/v1/shares/timed-papers/" + token),
  stageImport: (file: File) => {
    const body = new FormData();
    body.append("file", file);
    return request<ImportBatch>("/api/v1/admin/imports", { method: "POST", body });
  },
  commitImport: (id: string) => request<{ imported: number; skipped: number; invalid: number }>("/api/v1/admin/imports/" + id + "/commit", { method: "POST" }),
  createProblem: (body: unknown) => request<CreatedProblemVersion>("/api/v1/admin/problems", { method: "POST", body: JSON.stringify(body) }),
  createProblemVersion: (problemId: string, body: unknown) => request<CreatedProblemVersion>("/api/v1/admin/problems/" + problemId + "/versions", { method: "POST", body: JSON.stringify(body) }),
  updateDraftProblem: (versionId: string, body: unknown) => request<CreatedProblemVersion>("/api/v1/admin/problems/versions/" + versionId, { method: "PUT", body: JSON.stringify(body) }),
  publishDraft: (versionId: string) => request<CreatedProblemVersion>("/api/v1/admin/problems/versions/" + versionId + "/publish", { method: "POST" }),
  startAiRun: (problemVersionId: string, testCaseCount: number, autoPublish = false, sampleCount = 0) =>
    request<AiRun>("/api/v1/admin/ai-runs", {
      method: "POST",
      body: JSON.stringify({ problemVersionId, testCaseCount, autoPublish, sampleCount }),
    }),
  /** 查询单次 AI 录题流程的最新状态。 */
  aiRun: (runId: string) => request<AiRun>("/api/v1/admin/ai-runs/" + runId),
  /** 按题目草稿恢复当前 AI 运行；没有运行时返回空响应。 */
  activeAiRun: (problemVersionId: string) => request<AiRun | undefined>("/api/v1/admin/ai-runs/by-version/" + problemVersionId),
  /** 取消草稿上的当前 AI 运行。 */
  cancelAiRun: (runId: string) => request<AiRun>("/api/v1/admin/ai-runs/" + runId + "/cancel", { method: "POST" }),
  createWorker: (body: { name: string; slots: number; aiSlots: number }) =>
    request<CreatedWorker>("/api/v1/admin/workers", { method: "POST", body: JSON.stringify(body) }),
};

export function captchaUrl(): string {
  return "/api/v1/auth/captcha?nonce=" + Date.now();
}
