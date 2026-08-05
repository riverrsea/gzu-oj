import { defineConfig, devices } from "@playwright/test";

/** 宿主机 Chrome 的可选调试端点，供 WSL 缺失本机图形依赖时复用浏览器。 */
const remoteBrowserEndpoint = process.env.PW_TEST_CONNECT_WS_ENDPOINT;

/** 前端工作区的浏览器回归配置，独立启动 Vite 并保留失败证据。 */
export default defineConfig({
  testDir: "./e2e",
  timeout: 30_000,
  forbidOnly: Boolean(process.env.CI),
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: "http://127.0.0.1:5174",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    // 设置后由 Playwright 复用宿主机调试浏览器；未设置时沿用本地 Chromium。
    ...(remoteBrowserEndpoint ? { connectOptions: { wsEndpoint: remoteBrowserEndpoint } } : {}),
  },
  projects: [
    { name: "desktop", use: { ...devices["Desktop Chrome"] } },
    // 使用 Chromium 模拟移动视口，避免将回归范围绑定到额外的 WebKit 下载。
    { name: "mobile", use: { ...devices["iPhone 13"], browserName: "chromium" } },
  ],
  webServer: {
    command: "npm run dev -- --port 5174",
    url: "http://127.0.0.1:5174",
    reuseExistingServer: !process.env.CI,
    timeout: 30_000,
  },
});
