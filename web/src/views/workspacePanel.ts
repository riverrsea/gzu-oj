import type { JudgeLanguage, JudgeStatus, LanguageLimit, ProblemDetail, Submission } from "../api/types";

/** 代码草稿在本地存储中的生命周期状态。 */
export type CodeSaveState = "saved" | "pending" | "saving" | "error";

/** 做题工作区中可被停靠面板共享的状态和操作。 */
export interface WorkspacePanelContext {
  /** 当前锁定版本的题面。 */
  problem: ProblemDetail | undefined;
  /** 已清理危险标签的 Markdown HTML。 */
  renderedStatement: string;
  /** 当前语言的资源限制。 */
  activeLimit: LanguageLimit | undefined;
  /** 当前编辑语言。 */
  language: JudgeLanguage;
  /** 当前编辑器源码。 */
  code: string;
  /** 编辑器字号。 */
  fontSize: number;
  /** 是否使用深色主题。 */
  dark: boolean;
  /** 编辑器设置菜单是否展开。 */
  settingsOpen: boolean;
  /** 可编辑公开测试输入。 */
  runInputs: string[];
  /** 当前选中的公开测试用例下标。 */
  activeCase: number;
  /** 最近一次公开运行结果。 */
  runSubmission: Submission | undefined;
  /** 最近一次正式提交结果。 */
  submitSubmission: Submission | undefined;
  /** 是否正在运行公开测试。 */
  running: boolean;
  /** 是否正在提交全部测试点。 */
  submitting: boolean;
  /** 代码草稿的本地自动保存状态。 */
  codeSaveState: CodeSaveState;
  /** 终态集合。 */
  terminalStatuses: Set<JudgeStatus>;
  /** 更新源码。 */
  setCode: (value: string) => void;
  /** 更新编程语言。 */
  setLanguage: (value: JudgeLanguage) => void;
  /** 更新字号。 */
  setFontSize: (value: number) => void;
  /** 切换编辑器设置菜单。 */
  toggleSettings: () => void;
  /** 重置源码模板。 */
  resetCode: () => void;
  /** 更新指定公开测试输入。 */
  setRunInput: (index: number, value: string) => void;
  /** 新增一个公开测试输入。 */
  addRunInput: () => void;
  /** 切换当前公开测试用例。 */
  setActiveCase: (index: number) => void;
  /** 收藏当前题目。 */
  favorite: () => void;
  /** 请求整个工作区进入全屏。 */
  fullscreen: () => void;
}

/** Dockview 面板的业务类型。 */
export type WorkspacePanelKind = "statement" | "code" | "cases" | "result" | "submit";
