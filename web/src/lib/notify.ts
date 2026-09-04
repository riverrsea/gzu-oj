import { ref } from "vue";

/** 页面级轻提示的级别。 */
export type ToastKind = "success" | "error" | "warning" | "info";

/** 页面级轻提示数据。 */
export interface ToastItem {
  id: number;
  kind: ToastKind;
  message: string;
}

/** 当前页面待展示的轻提示列表。 */
export const toasts = ref<ToastItem[]>([]);
let nextToastId = 1;
const toastTimers = new Map<number, number>();

/** 从待展示列表中移除提示，并清理其自动关闭计时器。 */
export function removeToast(id: number): void {
  const timer = toastTimers.get(id);
  if (timer !== undefined) window.clearTimeout(timer);
  toastTimers.delete(id);
  toasts.value = toasts.value.filter((item) => item.id !== id);
}

/** 使用项目内置 Toast 替代第三方消息组件。 */
function pushToast(kind: ToastKind, message: string): void {
  const id = nextToastId++;
  toasts.value = [...toasts.value, { id, kind, message }];
  const timer = window.setTimeout(() => removeToast(id), 3600);
  toastTimers.set(id, timer);
}

export const toast = {
  success(message: string): void { pushToast("success", message); },
  error(message: string): void { pushToast("error", message); },
  warning(message: string): void { pushToast("warning", message); },
  info(message: string): void { pushToast("info", message); },
};

/** 使用浏览器原生确认框完成无需业务状态的确认操作。 */
export function confirmAction(message: string): Promise<void> {
  return window.confirm(message) ? Promise.resolve() : Promise.reject("cancel");
}

/** 使用浏览器原生输入框收集短文本口令。 */
export function promptAction(message: string): Promise<string> {
  const value = window.prompt(message);
  return value === null ? Promise.reject("cancel") : Promise.resolve(value);
}
