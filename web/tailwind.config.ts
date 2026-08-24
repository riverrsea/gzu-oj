import type { Config } from "tailwindcss";

/**
 * 项目前端的 Tailwind 配置。
 *
 * Element Plus 已经提供了表单和表格的基础样式，因此关闭 Tailwind
 * 的 preflight，避免全局重置覆盖 Element Plus 和 CodeMirror 的交互细节。
 */
const config: Config = {
  content: ["./index.html", "./src/**/*.{vue,ts}"],
  corePlugins: { preflight: false },
  darkMode: ["selector", '[data-theme="dark"]'],
  theme: {
    extend: {
      colors: {
        ink: "var(--text)",
        paper: "var(--surface)",
        canvas: "var(--bg)",
        line: "var(--border)",
        quiet: "var(--muted)",
        brand: "var(--accent)",
        "brand-strong": "var(--accent-strong)",
      },
      boxShadow: {
        panel: "var(--shadow)",
        float: "0 18px 45px rgba(15, 58, 49, 0.12)",
      },
      borderRadius: {
        panel: "12px",
      },
    },
  },
};

export default config;
