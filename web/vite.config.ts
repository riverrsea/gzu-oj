import { defineConfig, loadEnv } from "vite";
import vue from "@vitejs/plugin-vue";

/** 允许本地联调将 Vite 的同源 API 代理定向到指定控制端。 */
export default defineConfig(({ mode }) => {
  /** 读取 Vite 公开环境变量；默认值保持开发环境的 8080 端口。 */
  const environment = loadEnv(mode, process.cwd(), "VITE_");
  const apiProxyTarget = environment.VITE_API_PROXY_TARGET || "http://127.0.0.1:8080";

  return {
    plugins: [vue()],
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
