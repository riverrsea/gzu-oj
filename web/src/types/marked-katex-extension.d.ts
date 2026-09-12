/**
 * `marked-katex-extension` 把包内 `src/index.ts` 直接作为 types 暴露，
 * 于是本项目的 `noUnusedParameters` 会去检查第三方源码并报 TS6133。
 * 这里提供一份等价的类型声明，把该模块的类型收敛到本项目自己的声明上。
 */
declare module "marked-katex-extension" {
  import type { KatexOptions } from "katex";
  import type { MarkedExtension } from "marked";

  /** 扩展选项：KaTeX 原生选项，外加是否允许 `$...$` 前后没有空格。 */
  export interface MarkedKatexOptions extends KatexOptions {
    nonStandard?: boolean;
  }

  /** 创建 marked 的 KaTeX 扩展。 */
  export default function markedKatex(options?: MarkedKatexOptions): MarkedExtension;
}
