import { reactive } from "vue";

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
  /** 返回题库回调。 */
  back?: () => void | Promise<void>;
  /** 执行公开测试的回调。 */
  run?: () => void | Promise<void>;
  /** 提交全部测试点的回调。 */
  submit?: () => void | Promise<void>;
}

/** 应用壳层与做题页共享的顶端栏状态。 */
export const workspaceToolbar = reactive<WorkspaceToolbarState>({
  active: false,
  ready: false,
  running: false,
  submitting: false,
  coolingDown: false,
});

/** 做题页卸载时清理顶端栏回调，避免旧页面继续响应点击。 */
export function resetWorkspaceToolbar(): void {
  workspaceToolbar.active = false;
  workspaceToolbar.ready = false;
  workspaceToolbar.running = false;
  workspaceToolbar.submitting = false;
  workspaceToolbar.coolingDown = false;
  workspaceToolbar.back = undefined;
  workspaceToolbar.run = undefined;
  workspaceToolbar.submit = undefined;
}
