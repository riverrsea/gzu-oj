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
async function mockApi(page: Page): Promise<void> {
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
    if (path === "/api/v1/problems") return json([problem]);
    if (path === "/api/v1/problems/" + problem.id || path === "/api/v1/problems/versions/" + problem.versionId) return json(problemDetail);
    if (path === "/api/v1/contests") return json([{
      id: "33333333-3333-4333-8333-333333333333", title: "公开训练赛", visibility: "PUBLIC", ownerUsername: "管理员",
      startsAt: "2026-08-05T09:00:00Z", endsAt: "2026-08-05T11:00:00Z", phase: "UPCOMING",
      maxParticipants: 5, participantCount: 1, joined: true, problems: [{ ordinal: 1, problemId: problem.id, versionId: problem.versionId, title: problem.title }], myScores: null, ranking: null,
    }]);
    if (path === "/api/v1/timed-papers") return json([{ id: "44444444-4444-4444-8444-444444444444", title: "模拟套卷", durationMinutes: 90, problems: [{ ordinal: 1, problemId: problem.id, versionId: problem.versionId, title: problem.title }] }]);
    if (path === "/api/v1/runs" && request.method() === "POST") {
      const body = request.postDataJSON() as { problemId?: string; problemVersionId?: string };
      if (body.problemId !== problem.id || body.problemVersionId !== problem.versionId) {
        return json({ code: "INVALID_PROBLEM_VERSION", message: "运行请求未锁定工作区版本" }, 400);
      }
      return json({
        id: "55555555-5555-4555-8555-555555555555", problemId: problem.id, problemVersionId: problem.versionId, executionMode: "RUN", language: "CPP17", status: "QUEUED", score: 0, compileMessage: null, createdAt: "2026-08-05T09:00:00Z", finishedAt: null, testCases: [],
      });
    }
    if (path === "/api/v1/submissions/55555555-5555-4555-8555-555555555555") return json({
      id: "55555555-5555-4555-8555-555555555555", problemId: problem.id, problemVersionId: problem.versionId, executionMode: "RUN", language: "CPP17", status: "AC", score: 0, compileMessage: null, createdAt: "2026-08-05T09:00:00Z", finishedAt: "2026-08-05T09:00:01Z",
      testCases: [{ ordinal: 1, status: "AC", score: 0, timeMs: 1, memoryKiB: 1024, message: null, input: "1 2\n", actualOutput: "3\n" }],
    });
    return json({ code: "UNMOCKED", message: "未配置的浏览器测试请求", timestamp: "2026-08-05T09:00:00Z" }, 404);
  });
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
  await expect(page.getByText("AC").first()).toBeVisible({ timeout: 5_000 });
  await expect(page.getByText("实际输出")).toBeVisible();
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
  await page.getByRole("button", { name: "结果" }).click();
  await expect(page.getByText("测试与结果")).toBeVisible();
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
  await expect(page.getByRole("heading", { name: "训练中心" })).toBeVisible();
  await expect(page.getByText("公开训练赛")).toBeVisible();
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
