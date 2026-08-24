import path from "node:path";
import { defineConfig, loadEnv } from "vite";
import vue from "@vitejs/plugin-vue";

/** 允许本地联调将 Vite 的同源 API 代理定向到指定控制端。 */
export default defineConfig(({ mode }) => {
  /** 读取 Vite 公开环境变量；默认值保持开发环境的 8080 端口。 */
  const environment = loadEnv(mode, process.cwd(), "VITE_");
  const apiProxyTarget = environment.VITE_API_PROXY_TARGET || "http://127.0.0.1:8080";

  return {
    plugins: [vue()],
    resolve: {
      /** Monaco 0.56 的 Worker 子路径需要显式指向实际文件。 */
      alias: {
        "monaco-editor/esm/vs/editor/editor.api.js": path.resolve(process.cwd(), "node_modules/monaco-editor/esm/vs/editor/editor.api.js"),
        "monaco-editor/esm/vs/editor/editor.worker.js": path.resolve(process.cwd(), "node_modules/monaco-editor/esm/vs/editor/editor.worker.js"),
        "monaco-editor/esm/vs/languages/definitions/cpp/register.js": path.resolve(process.cwd(), "node_modules/monaco-editor/esm/vs/languages/definitions/cpp/register.js"),
        "monaco-editor/esm/vs/languages/definitions/java/register.js": path.resolve(process.cwd(), "node_modules/monaco-editor/esm/vs/languages/definitions/java/register.js"),
        "monaco-editor/esm/vs/languages/definitions/python/register.js": path.resolve(process.cwd(), "node_modules/monaco-editor/esm/vs/languages/definitions/python/register.js"),
      },
    },
    server: {
      port: 5173,
      proxy: {
        "/api": apiProxyTarget,
        "/actuator": apiProxyTarget,
      },
    },
    build: {
      target: "es2022",
      sourcemap: true,
    },
  };
});
