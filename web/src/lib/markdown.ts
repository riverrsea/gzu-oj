import DOMPurify from "dompurify";
import { marked } from "marked";
import markedKatex from "marked-katex-extension";
import "katex/dist/katex.min.css";

/**
 * 全站统一的题面 Markdown 渲染器。
 *
 * 题面里的公式在数据库中是原始 LaTeX（`$...$` 行内、`$$...$$` 块级），
 * 采集自 N 诺的题面还可能出现紧贴中文、不带空格的写法（例如 `边长$a,b,c$，`），
 * 因此必须开启 nonStandard 才能按 $ 定界符识别。
 */
marked.use(
  markedKatex({
    // 公式写错时在页面上显示红色错误文本，而不是让整页渲染失败。
    throwOnError: false,
    // 允许 $...$ 前后没有空格，兼容从题面直接采集到的原始 LaTeX。
    nonStandard: true,
    // 爬取内容来源复杂，未知命令只忽略告警，不影响其余公式渲染。
    strict: false,
  }),
);

/** 把 Markdown 渲染成可安全插入页面的 HTML。 */
export function renderMarkdown(source: string): string {
  const html = marked.parse(source, { async: false }) as string;
  return DOMPurify.sanitize(html, {
    // KaTeX 同时输出 HTML（视觉渲染）和 MathML（无障碍），MathML 需要放开 mathMl 标签集。
    // 这里刻意不放开 svg 配置：KaTeX 不使用 SVG，少开一个标签集就少一分注入面。
    USE_PROFILES: { html: true, mathMl: true },
    // KaTeX 的 MathML 用 <semantics> 与 <annotation> 包裹，二者不在 DOMPurify 的 mathMl 白名单里。
    ADD_TAGS: ["semantics", "annotation"],
  });
}
