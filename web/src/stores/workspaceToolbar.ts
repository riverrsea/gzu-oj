import { reactive } from "vue";
import type { TimedAttemptStatus } from "../api/types";

/** 全局顶端栏使用的做题操作状态。由做题页注册，应用壳层负责展示。 */
export interface WorkspaceToolbarState {
  /** 当前是否处于做题页。 */
  active: boolean;
  /** 当前题目是否已加载，可以执行操作。 */
  ready: boolean;
  /** 是否正在运行公开测试。 */
  running: boolean;
  /** 是否正在提交全部测试点。 */
  submitting: boolean;
  /** 是否处于判题结束后的短暂冷却期。 */
  coolingDown: boolean;
  /** 个人计时上下文的当前状态；普通练习和训练赛为空。 */
  timedAttemptStatus?: TimedAttemptStatus;
  /** 个人计时上下文的本地实时剩余秒数。 */
  timedAttemptRemainingSeconds: number;
  /** 个人计时状态操作是否正在请求后端。 */
  timedAttemptActionLoading: boolean;
  /** 返回题库回调。 */
  back?: () => void | Promise<void>;
  /** 执行公开测试的回调。 */
  run?: () => void | Promise<void>;
  /** 提交全部测试点的回调。 */
  submit?: () => void | Promise<void>;
  /** 暂停当前个人计时。 */
  pauseTimedAttempt?: () => void | Promise<void>;
  /** 继续当前个人计时。 */
  resumeTimedAttempt?: () => void | Promise<void>;
  /** 提前结束当前个人计时。 */
  finishTimedAttempt?: () => void | Promise<void>;
  /** 打开或关闭做题页题目列表抽屉。 */
  toggleProblemList?: () => void;
  /** 题目列表抽屉是否已经展开。 */
  problemListOpen: boolean;
  /** 切换到上一道题。 */
  previousProblem?: () => void | Promise<void>;
  /** 切换到下一道题。 */
  nextProblem?: () => void | Promise<void>;
  /** 是否存在上一道题。 */
  canPreviousProblem: boolean;
  /** 是否存在下一道题。 */
  canNextProblem: boolean;
}

/** 应用壳层与做题页共享的顶端栏状态。 */
export const workspaceToolbar = reactive<WorkspaceToolbarState>({
  active: false,
  ready: false,
  running: false,
  submitting: false,
  coolingDown: false,
  timedAttemptRemainingSeconds: 0,
  timedAttemptActionLoading: false,
  problemListOpen: false,
  canPreviousProblem: false,
  canNextProblem: false,
});

/** 做题页卸载时清理顶端栏回调，避免旧页面继续响应点击。 */
export function resetWorkspaceToolbar(): void {
  workspaceToolbar.active = false;
  workspaceToolbar.ready = false;
  workspaceToolbar.running = false;
  workspaceToolbar.submitting = false;
  workspaceToolbar.coolingDown = false;
  workspaceToolbar.timedAttemptStatus = undefined;
  workspaceToolbar.timedAttemptRemainingSeconds = 0;
  workspaceToolbar.timedAttemptActionLoading = false;
  workspaceToolbar.back = undefined;
  workspaceToolbar.run = undefined;
  workspaceToolbar.submit = undefined;
  workspaceToolbar.pauseTimedAttempt = undefined;
  workspaceToolbar.resumeTimedAttempt = undefined;
  workspaceToolbar.finishTimedAttempt = undefined;
  workspaceToolbar.toggleProblemList = undefined;
  workspaceToolbar.problemListOpen = false;
  workspaceToolbar.previousProblem = undefined;
  workspaceToolbar.nextProblem = undefined;
  workspaceToolbar.canPreviousProblem = false;
  workspaceToolbar.canNextProblem = false;
}
