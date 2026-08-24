/// <reference types="vite/client" />

declare module "*.vue" {
  import type { DefineComponent } from "vue";
  const component: DefineComponent<Record<string, never>, Record<string, never>, unknown>;
  export default component;
}

/** Monaco 0.56 的核心入口通过 package exports 暴露，补充 Vite 子路径的类型声明。 */
declare module "monaco-editor/esm/vs/editor/editor.api.js" {
  export * from "monaco-editor";
}
