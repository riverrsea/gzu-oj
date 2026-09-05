import { expect, test, type Page } from "@playwright/test";

/** 供浏览器回归使用的已发布题目。 */
const problem = {
  id: "11111111-1111-4111-8111-111111111111",
  versionId: "22222222-2222-4222-8222-222222222222",
  externalKey: "e2e-a-plus-b",
  title: "A + B",
  school: "贵州大学",
  year: 2026,
  tags: ["模拟", "基础"],
  difficulty: "EASY",
};

/** 个人计时和训练赛中用于验证 B 序号的第二道题。 */
const secondProblem = {
  ...problem,
  id: "88888888-8888-4888-8888-888888888888",
  versionId: "99999999-9999-4999-8999-999999999999",
  externalKey: "e2e-sort",
  title: "整数排序",
};

/** 返回做题工作区所需的完整题目模型。 */
const problemDetail = {
  ...problem,
  versionNumber: 1,
  sourceUrl: null,
  statementMarkdown: "# A + B\n\n读入两个整数，输出它们的和。",
  languageLimits: [
    { language: "C17", timeLimitMs: 1000, memoryLimitMiB: 128 },
    { language: "CPP17", timeLimitMs: 1000, memoryLimitMiB: 128 },
    { language: "JAVA21", timeLimitMs: 2000, memoryLimitMiB: 256 },
    { language: "PYTHON3", timeLimitMs: 3000, memoryLimitMiB: 256 },
  ],
  samples: [{ ordinal: 1, input: "1 2\n", output: "3\n" }],
  dataNotice: null,
};

/** 设置 API 拦截器，使页面布局和交互回归不依赖外部服务。 */
async function mockApi(page: Page): Promise<{ lastRunTimedAttemptId?: string }> {
  const timedPaperId = "44444444-4444-4444-8444-444444444444";
  const timedAttemptId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  const timedProblems = [problem, secondProblem].map((item, index) => ({
    ordinal: index + 1,
    problemId: item.id,
    versionId: item.versionId,
    title: item.title,
  }));
  const timedState: { started: boolean; status: "RUNNING" | "PAUSED" | "FINISHED"; remainingSeconds: number; lastRunTimedAttemptId?: string } = {
    started: false,
    status: "RUNNING",
    remainingSeconds: 5_400,
  };
  /** 返回与当前模拟状态一致的个人计时响应。 */
  const timedAttempt = () => ({
    id: timedAttemptId,
    paper: {id: timedPaperId, title: "模拟套卷", durationMinutes: 90, problems: timedProblems},
    startedAt: "2099-08-05T09:00:00Z",
    expiresAt: "2099-08-05T10:30:00Z",
    finished: timedState.status === "FINISHED",
    status: timedState.status,
    remainingSeconds: timedState.status === "FINISHED" ? 0 : timedState.remainingSeconds,
    scores: {[problem.id]: 100, [secondProblem.id]: 60},
    totalScore: 160,
    maximumScore: 200,
  });
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const json = (body: unknown, status = 200) => route.fulfill({
      status,
      contentType: "application/json",
      body: JSON.stringify(body),
    });

    if (path === "/api/v1/auth/me") return json({ id: "admin-id", username: "管理员", role: "ADMIN" });
    if (path === "/api/v1/csrf") return json({ headerName: "X-XSRF-TOKEN", token: "e2e-csrf-token" });
    if (path === "/api/v1/admin/problems") return json({
      items: [{
        problemId: problem.id, versionId: problem.versionId, externalKey: problem.externalKey, title: problem.title,
        school: problem.school, year: problem.year, tags: problem.tags, difficulty: problem.difficulty,
        versionNumber: 1, status: "PUBLISHED", testCaseCount: 8, scoreSum: 100, sampleCount: 1,
        createdAt: "2026-08-05T09:00:00Z", publishedAt: "2026-08-05T09:10:00Z",
      }],
      page: 0,
      size: 20,
      total: 1,
    });
    if (path === "/api/v1/admin/problems/versions/" + problem.versionId) return json({
      problemId: problem.id,
      versionId: problem.versionId,
      versionNumber: 1,
      externalKey: problem.externalKey,
      title: problem.title,
      school: problem.school,
      year: problem.year,
      tags: problem.tags,
      difficulty: problem.difficulty,
      sourceUrl: null,
      statementMarkdown: problemDetail.statementMarkdown,
      timeLimitMs: 1000,
      memoryLimitMiB: 128,
      status: "PUBLISHED",
      contentSha256: "a".repeat(64),
      activeAiRun: false,
      dataNotice: null,
      testCases: [{ ordinal: 1, input: "1 2\n", output: "3\n", score: 100, sample: true }],
    });
    if (path === "/api/v1/problems") return json([problem, secondProblem]);
    if (path === "/api/v1/problems/" + problem.id || path === "/api/v1/problems/versions/" + problem.versionId) return json(problemDetail);
    if (path === "/api/v1/problems/" + secondProblem.id || path === "/api/v1/problems/versions/" + secondProblem.versionId) {
      return json({...problemDetail, ...secondProblem});
    }
    if (path === "/api/v1/contests") return json([{
      id: "33333333-3333-4333-8333-333333333333", title: "公开训练赛", visibility: "PUBLIC", ownerUsername: "管理员", createdAt: "2026-08-05T08:00:00Z",
      startsAt: "2026-08-05T09:00:00Z", endsAt: "2026-08-05T11:00:00Z", phase: "UPCOMING",
      maxParticipants: 5, participantCount: 1, joined: true,
    }]);
    if (path === "/api/v1/timed-papers") return json([{id: timedPaperId, title: "模拟套卷", durationMinutes: 90, problems: timedProblems}]);
    if (path === "/api/v1/timed-papers/attempts" && request.method() === "GET") return json(timedState.started ? [timedAttempt()] : []);
    if (path === `/api/v1/timed-papers/${timedPaperId}/attempts` && request.method() === "POST") {
      timedState.started = true;
      return json(timedAttempt());
    }
    if (path === `/api/v1/timed-papers/attempts/${timedAttemptId}` && request.method() === "GET") return json(timedAttempt());
    if (path === `/api/v1/timed-papers/attempts/${timedAttemptId}/pause` && request.method() === "POST") {
      timedState.status = "PAUSED";
      return json(timedAttempt());
    }
    if (path === `/api/v1/timed-papers/attempts/${timedAttemptId}/resume` && request.method() === "POST") {
      timedState.status = "RUNNING";
      return json(timedAttempt());
    }
    if (path === `/api/v1/timed-papers/attempts/${timedAttemptId}/finish` && request.method() === "POST") {
      timedState.status = "FINISHED";
      return json(timedAttempt());
    }
    if (path === "/api/v1/runs" && request.method() === "POST") {
      const body = request.postDataJSON() as { problemId?: string; problemVersionId?: string; expectedOutputs?: string[]; timedPaperAttemptId?: string };
      timedState.lastRunTimedAttemptId = body.timedPaperAttemptId;
      if (body.problemId !== problem.id || body.problemVersionId !== problem.versionId) {
        return json({ code: "INVALID_PROBLEM_VERSION", message: "运行请求未锁定工作区版本" }, 400);
      }
      if (body.expectedOutputs?.[0] !== "3\n") {
        return json({ code: "MISSING_EXPECTED_OUTPUT", message: "运行请求未携带样例输出" }, 400);
      }
      return json({
        id: "55555555-5555-4555-8555-555555555555", problemId: problem.id, problemVersionId: problem.versionId, executionMode: "RUN", language: "CPP17", status: "QUEUED", score: 0, compileMessage: null, createdAt: "2026-08-05T09:00:00Z", finishedAt: null, testCases: [],
      });
    }
    if (path === "/api/v1/submissions" && request.method() === "POST") return json({
      id: "66666666-6666-4666-8666-666666666666", problemId: problem.id, problemVersionId: problem.versionId, executionMode: "SUBMIT", language: "CPP17", status: "QUEUED", score: 0, compileMessage: null, createdAt: "2026-08-05T09:01:00Z", finishedAt: null, testCases: [],
    });
    if (path === "/api/v1/submissions/55555555-5555-4555-8555-555555555555") return json({
      id: "55555555-5555-4555-8555-555555555555", problemId: problem.id, problemVersionId: problem.versionId, executionMode: "RUN", language: "CPP17", status: "AC", score: 0, compileMessage: null, createdAt: "2026-08-05T09:00:00Z", finishedAt: "2026-08-05T09:00:01Z",
      testCases: [{ ordinal: 1, status: "AC", score: 0, timeMs: 1, memoryKiB: 1024, message: null, input: "1 2\n", actualOutput: "3\n" }],
    });
    if (path === "/api/v1/submissions/66666666-6666-4666-8666-666666666666") return json({
      id: "66666666-6666-4666-8666-666666666666", problemId: problem.id, problemVersionId: problem.versionId, executionMode: "SUBMIT", language: "CPP17", status: "WA", score: 40, compileMessage: null, createdAt: "2026-08-05T09:01:00Z", finishedAt: "2026-08-05T09:01:01Z",
      testCases: [{ ordinal: 1, status: "AC", score: 40, timeMs: 1, memoryKiB: 1024, message: "判题完成", input: null, actualOutput: null }],
    });
    return json({ code: "UNMOCKED", message: "未配置的浏览器测试请求", timestamp: "2026-08-05T09:00:00Z" }, 404);
  });
  return timedState;
}

/** 验证注销后服务端清除旧令牌时，下一次登录会重新请求 CSRF。 */
test("退出后重新登录会刷新 CSRF 令牌", async ({ page }) => {
  let authenticated = true;
  let currentCsrf: string | null = null;
  let csrfRequests = 0;
  const user = { id: "user-id", username: "管理员", role: "ADMIN" };

  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const json = (body: unknown, status = 200) => route.fulfill({
      status,
      contentType: "application/json",
      body: JSON.stringify(body),
    });

    if (path === "/api/v1/auth/me") return authenticated ? json(user) : json({ code: "UNAUTHENTICATED", message: "请先登录" }, 401);
    if (path === "/api/v1/csrf") {
      csrfRequests += 1;
      currentCsrf = `csrf-${csrfRequests}`;
      return json({ headerName: "X-XSRF-TOKEN", token: currentCsrf });
    }
    if (path === "/api/v1/auth/captcha") {
      return route.fulfill({ status: 200, contentType: "image/svg+xml", body: "<svg xmlns='http://www.w3.org/2000/svg' />" });
    }
    if (path === "/api/v1/problems") return json([]);
    if (path === "/api/v1/auth/logout" && request.method() === "POST") {
      if (request.headers()["x-xsrf-token"] !== currentCsrf) return json({ code: "CSRF_INVALID", message: "CSRF 令牌无效" }, 403);
      authenticated = false;
      currentCsrf = null;
      return route.fulfill({ status: 204, body: "" });
    }
    if (path === "/api/v1/auth/login" && request.method() === "POST") {
      if (request.headers()["x-xsrf-token"] !== currentCsrf) return json({ code: "CSRF_INVALID", message: "CSRF 令牌无效" }, 403);
      authenticated = true;
      return json(user);
    }
    return json({ code: "UNMOCKED", message: "未配置的浏览器测试请求", timestamp: "2026-08-05T09:00:00Z" }, 404);
  });

  await page.goto("/problems");
  await expect(page.getByTitle("退出登录")).toBeVisible();
  await page.getByTitle("退出登录").click();
  await page.goto("/login");

  const inputs = page.locator("input");
  await inputs.nth(0).fill("admin");
  await inputs.nth(1).fill("password");
  await inputs.nth(2).fill("12345");
  await page.locator("form button[type='submit']").click();

  await expect(page.getByTitle("退出登录")).toBeVisible();
  expect(csrfRequests).toBe(2);
});

/** 验证注册、邮箱验证和找回密码在同一张认证卡片中连续切换。 */
test("认证卡片可完成注册验证和找回密码切换", async ({ page }) => {
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const json = (body: unknown, status = 200) => route.fulfill({
      status,
      contentType: "application/json",
      body: JSON.stringify(body),
    });

    if (path === "/api/v1/auth/me") return json({ code: "UNAUTHENTICATED", message: "请先登录" }, 401);
    if (path === "/api/v1/auth/captcha") {
      return route.fulfill({ status: 200, contentType: "image/svg+xml", body: "<svg xmlns='http://www.w3.org/2000/svg' />" });
    }
    if (path === "/api/v1/csrf") return json({ headerName: "X-XSRF-TOKEN", token: "auth-flow-csrf" });
    if (path === "/api/v1/auth/register" && request.method() === "POST") return json({ message: "注册成功，请查收邮箱验证码" });
    if (path === "/api/v1/auth/verify-email" && request.method() === "POST") return json({ message: "邮箱验证成功" });
    if (path === "/api/v1/auth/password-reset/request" && request.method() === "POST") {
      return json({ message: "若邮箱已注册，重置邮件将很快送达" });
    }
    return json({ code: "UNMOCKED", message: "未配置的浏览器测试请求" }, 404);
  });

  await page.goto("/login");
  await expect(page.locator(".auth-copy")).toHaveCount(0);
  await expect(page.getByRole("link", { name: "登录" })).toHaveCount(0);
  await page.getByRole("button", { name: "创建账号" }).click();
  await expect(page).toHaveURL(/\/register/);
  await page.getByLabel("用户名").fill("candidate");
  await page.getByLabel("邮箱").fill("candidate@example.com");
  await page.getByLabel("密码").fill("password-123");
  await page.getByLabel("图形验证码").fill("ABCDE");
  await page.getByRole("button", { name: "继续" }).click();
  await expect(page.getByRole("heading", { name: "验证邮箱" })).toBeVisible();
  await page.getByLabel("六位验证码").fill("123456");
  await page.getByRole("button", { name: "完成验证" }).click();
  await expect(page.getByRole("heading", { name: "登录" })).toBeVisible();

  await page.getByRole("button", { name: "忘记密码？" }).click();
  await expect(page.getByRole("heading", { name: "找回密码" })).toBeVisible();
  await page.getByLabel("邮箱").fill("candidate@example.com");
  await page.getByLabel("图形验证码").fill("ABCDE");
  await page.getByRole("button", { name: "发送重置邮件" }).click();
  await expect(page.getByRole("heading", { name: "邮件已发送" })).toBeVisible();
  await page.getByRole("button", { name: "返回登录" }).last().click();
  await expect(page.getByRole("heading", { name: "登录" })).toBeVisible();
});

/** 验证桌面题库、做题工作区和公开运行抽屉。 */
test("桌面端题库和做题工作区可操作", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "desktop", "此用例只验证桌面工作区");
  await mockApi(page);
  await page.goto("/problems");
  await expect(page.getByRole("heading", { name: "题库" })).toBeVisible();
  await expect(page.getByText("A + B").first()).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath("problem-list-desktop.png"), fullPage: true });

  await page.getByText("A + B").first().click();
  await expect(page.getByRole("heading", { name: "A + B" })).toBeVisible();
  await page.getByRole("button", { name: "运行" }).click();
  await expect(page.getByText("通过").first()).toBeVisible({ timeout: 5_000 });
  await expect(page.getByText("预期结果")).toBeVisible();
  await expect(page.locator(".workspace-topbar-center")).not.toContainText("AC");
  await expect(page.locator(".workspace-topbar-center")).not.toContainText("WA");
  await page.getByRole("button", { name: "提交", exact: true }).click();
  await expect(page.getByText("提交结果：WA，40 分")).toBeVisible({ timeout: 5_000 });
  await page.screenshot({ path: testInfo.outputPath("workspace-desktop.png"), fullPage: true });
});

/** 验证移动端题目、代码、结果标签与无水平溢出布局。 */
test("移动端工作区可切换且没有水平溢出", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "mobile", "此用例只验证移动工作区");
  await mockApi(page);
  await page.goto("/problems/" + problem.id);
  await expect(page.getByRole("button", { name: "代码" })).toBeVisible();
  await page.getByRole("button", { name: "代码" }).click();
  await expect(page.getByTitle("编辑器设置")).toBeVisible();
  await page.getByRole("tab", { name: "测试结果" }).click();
  await expect(page.getByText("运行或提交后在此查看结果")).toBeVisible();
  const dimensions = await page.evaluate(() => ({ width: document.documentElement.clientWidth, scrollWidth: document.documentElement.scrollWidth }));
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.width + 1);
  await page.screenshot({ path: testInfo.outputPath("workspace-mobile.png"), fullPage: true });
});

/** 验证管理员界面和训练中心在同一前端契约下可加载。 */
test("管理员录题与训练中心可加载", async ({ page }) => {
  await mockApi(page);
  await page.goto("/admin/problems/new");
  await expect(page.getByRole("heading", { name: "新建题目草稿" })).toBeVisible();
  await expect(page.locator(".statement-builder")).toBeVisible();
  await expect(page.getByText("题目描述", { exact: true }).first()).toBeVisible();
  await expect(page.locator(".statement-preview")).toContainText("题目描述");
  await page.goto("/training");
  await expect(page.getByRole("heading", { name: "训练", exact: true })).toBeVisible();
  await expect(page.getByText("公开训练赛")).toBeVisible();
});

/** 验证个人计时从开始跳转到结束只保留一次作答，并同步顶栏状态与字母题号。 */
test("个人计时可暂停继续并提前结束", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "desktop", "此用例只验证桌面端完整计时顶栏");
  const timedState = await mockApi(page);

  await page.goto("/training");
  await page.getByRole("tab", {name: "个人计时"}).click();
  const paperCard = page.locator(".training-browser-item").filter({hasText: "模拟套卷"});
  await paperCard.click({position: {x: 20, y: 20}});
  const paperDetail = page.locator(".training-detail-drawer");
  await expect(paperDetail.getByRole("button", {name: "开始作答"})).toHaveCount(1);
  await paperDetail.getByRole("button", {name: "开始作答"}).click();

  await expect(page).toHaveURL(/\/problems\/11111111-1111-4111-8111-111111111111\?.*timedPaperAttemptId=aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa/);
  await page.getByRole("button", {name: "题目列表"}).click();
  const drawer = page.locator(".workspace-problem-drawer");
  await expect(drawer).toContainText("A.");
  await expect(drawer).toContainText("B.");
  await expect(drawer).toContainText("整数排序");
  await page.getByRole("button", {name: "关闭题目列表"}).click();

  await page.getByRole("button", {name: "暂停"}).click();
  await expect(page.getByText("计时已暂停")).toBeVisible();
  await expect(page.getByRole("button", {name: "运行"})).toBeDisabled();
  await expect(page.getByRole("button", {name: "提交", exact: true})).toBeDisabled();
  const frozenCountdown = await page.locator(".workspace-timed-countdown").textContent();
  await page.waitForTimeout(1_100);
  await expect(page.locator(".workspace-timed-countdown")).toHaveText(frozenCountdown ?? "");

  await page.getByRole("button", {name: "继续"}).click();
  await expect(page.getByText("计时已继续")).toBeVisible();
  await page.getByRole("button", {name: "运行"}).click();
  await expect(page.getByText("通过").first()).toBeVisible({timeout: 5_000});
  expect(timedState.lastRunTimedAttemptId).toBe("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

  await page.getByRole("button", {name: "提前结束"}).click();
  const finishDialog = page.getByRole("dialog", {name: "提前结束作答"});
  await expect(finishDialog).toBeVisible();
  await finishDialog.getByRole("button", {name: "确认结束"}).click();
  await expect(page.locator(".workspace-timed-countdown")).toHaveText("已结束");
  await expect(page.getByRole("button", {name: "运行"})).toBeDisabled();
  await expect(page.getByRole("button", {name: "提交", exact: true})).toBeDisabled();

  await page.goto("/training");
  await page.getByRole("tab", {name: "个人计时"}).click();
  const finishedCard = page.locator(".training-browser-item").filter({hasText: "模拟套卷"});
  await expect(finishedCard).toContainText("已结束");
  await expect(finishedCard.getByRole("button", {name: "查看结果"})).toHaveCount(0);
  await finishedCard.click({position: {x: 20, y: 20}});
  await expect(page.locator(".training-attempt-score")).toContainText("160/200");
  await expect(page.locator(".training-problem-ordinal")).toHaveText(["A", "B"]);
});

/** 验证训练赛发现条件、口令赛锁定摘要和邀请码加入入口。 */
test("训练赛可查询并使用邀请码加入口令赛", async ({ page }) => {
  await mockApi(page);
  const publicContest = {
    id: "33333333-3333-4333-8333-333333333333",
    title: "公开训练赛",
    visibility: "PUBLIC",
    ownerUsername: "管理员",
    createdAt: "2026-08-05T08:00:00Z",
    startsAt: "2099-08-05T09:00:00Z",
    endsAt: "2099-08-05T11:00:00Z",
    phase: "UPCOMING",
    maxParticipants: 5,
    participantCount: 1,
    joined: false,
  } as const;
  const passwordContest = {
    ...publicContest,
    id: "77777777-7777-4777-8777-777777777777",
    title: "算法邀请赛",
    visibility: "PASSWORD",
    ownerUsername: "教练",
    participantCount: 2,
    joined: false,
  } as const;
  let publicJoined = false;
  let joined = false;
  let protectedDetailRequests = 0;
  let publicJoinPassword: string | undefined;
  const listQueries: string[] = [];

  await page.route("**/api/v1/contests**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const json = (body: unknown, status = 200) => route.fulfill({
      status,
      contentType: "application/json",
      body: JSON.stringify(body),
    });

    if (path === "/api/v1/contests" && request.method() === "GET") {
      listQueries.push(url.search);
      const keyword = url.searchParams.get("keyword")?.toLocaleLowerCase("zh-CN") ?? "";
      const visibility = url.searchParams.get("visibility");
      const all = [{...publicContest, joined: publicJoined}, {...passwordContest, joined}];
      return json(all.filter((contest) =>
        (!visibility || contest.visibility === visibility) &&
        (!keyword || contest.title.toLocaleLowerCase("zh-CN").includes(keyword) || contest.ownerUsername.toLocaleLowerCase("zh-CN").includes(keyword)),
      ));
    }
    if (path === `/api/v1/contests/${publicContest.id}` && request.method() === "GET") {
      return json({
        ...publicContest,
        joined: publicJoined,
        participantCount: publicJoined ? 2 : 1,
        problems: [{ordinal: 1, problemId: problem.id, versionId: problem.versionId, title: problem.title}],
        myScores: null,
        ranking: null,
      });
    }
    if (path === `/api/v1/contests/${publicContest.id}/participants` && request.method() === "POST") {
      const body = request.postDataJSON() as {password?: string};
      publicJoinPassword = body.password;
      publicJoined = true;
      return json({
        ...publicContest,
        joined: true,
        participantCount: 2,
        problems: [{ordinal: 1, problemId: problem.id, versionId: problem.versionId, title: problem.title}],
        myScores: null,
        ranking: null,
      });
    }
    if (path === `/api/v1/contests/${passwordContest.id}` && request.method() === "GET") {
      protectedDetailRequests += 1;
      if (!joined) return json({code: "CONTEST_NOT_FOUND", message: "比赛不存在"}, 404);
    }
    if (path === `/api/v1/contests/${passwordContest.id}/participants` && request.method() === "POST") {
      const body = request.postDataJSON() as {password?: string};
      if (body.password !== "invite-2026") {
        return json({code: "CONTEST_PASSWORD_INVALID", message: "邀请码不正确"}, 403);
      }
      joined = true;
      return json({
        ...passwordContest,
        joined: true,
        participantCount: 3,
        problems: [{ordinal: 1, problemId: problem.id, versionId: problem.versionId, title: problem.title}],
        myScores: null,
        ranking: null,
      });
    }
    return route.fallback();
  });

  await page.goto("/training");
  await expect(page.getByText("公开训练赛")).toBeVisible();
  await expect(page.getByText("算法邀请赛")).toBeVisible();
  await expect(page.locator(".training-browser-count")).toHaveCount(0);
  await expect(page.locator(".training-tabs button > span")).toHaveCount(0);

  await page.locator(".training-browser-item").filter({hasText: "公开训练赛"}).click();
  await expect(page.getByRole("heading", {name: "题目"})).toBeVisible();
  await expect(page.locator(".training-problem-ordinal")).toHaveText("A");
  await expect(page.locator(".training-problem-score")).toHaveCount(0);
  await page.getByRole("button", {name: "加入比赛"}).click();
  await expect(page.getByText("已加入训练赛")).toBeVisible();
  expect(publicJoinPassword).toBeUndefined();
  await page.getByRole("button", {name: "关闭详情"}).click();

  await page.getByLabel("比赛类型").selectOption("PUBLIC");
  await page.getByRole("button", {name: "查询"}).click();
  await expect(page.getByText("公开训练赛")).toBeVisible();
  await expect(page.getByText("算法邀请赛")).toHaveCount(0);
  expect(listQueries.at(-1)).toBe("?visibility=PUBLIC");

  await page.getByRole("button", {name: "重置"}).click();
  await expect(page.getByText("算法邀请赛")).toBeVisible();
  expect(listQueries.at(-1)).toBe("");

  await page.getByLabel("比赛关键词").fill("不存在的比赛");
  await page.getByRole("button", {name: "查询"}).click();
  await expect(page.getByText("没有符合条件的训练赛")).toBeVisible();
  await page.getByRole("button", {name: "重置"}).click();

  await page.getByText("算法邀请赛").click();
  await expect(page.getByText("这是一场口令赛")).toBeVisible();
  expect(protectedDetailRequests).toBe(0);

  page.once("dialog", (dialog) => dialog.accept("wrong-code"));
  await page.getByRole("button", {name: "输入邀请码加入"}).click();
  await expect(page.getByText("邀请码不正确")).toBeVisible();
  await expect(page.getByText("这是一场口令赛")).toBeVisible();

  page.once("dialog", (dialog) => dialog.accept("invite-2026"));
  await page.getByRole("button", {name: "输入邀请码加入"}).click();
  await expect(page.getByText("已加入训练赛")).toBeVisible();
  await expect(page.getByRole("heading", {name: "题目"})).toBeVisible();
  await expect(page.getByText("A + B")).toBeVisible();

  await page.goto("/rankings");
  await expect(page.getByText("公开训练赛")).toBeVisible();
  expect(listQueries.at(-1)).toBe("?visibility=PUBLIC");
});

/** 验证管理功能使用独立路由，并在桌面和移动端保持可操作布局。 */
test("管理端功能页相互独立且没有水平溢出", async ({ page }, testInfo) => {
  await mockApi(page);
  await page.goto("/admin");
  await expect(page).toHaveURL(/\/admin\/problems$/);
  await expect(page.getByRole("heading", { name: "题库管理" })).toBeVisible();
  await expect(page.getByText("A + B").first()).toBeVisible();

  await page.getByRole("button", { name: "新版本" }).click();
  await expect(page.getByRole("heading", { name: "新建题目版本" })).toBeVisible();
  await expect(page.getByLabel("标题")).toHaveValue("A + B");

  await page.getByRole("link", { name: "批量导入" }).click();
  await expect(page.getByRole("heading", { name: "批量导入", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "题库管理" })).toHaveCount(0);

  await page.getByRole("link", { name: "AI 录题" }).click();
  await expect(page.getByRole("heading", { name: "AI 录题", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "批量导入", exact: true })).toHaveCount(0);

  const dimensions = await page.evaluate(() => ({
    width: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }));
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.width + 1);
  await page.screenshot({ path: testInfo.outputPath(`admin-${testInfo.project.name}.png`), fullPage: true });
});
