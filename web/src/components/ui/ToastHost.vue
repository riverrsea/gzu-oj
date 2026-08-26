<script setup lang="ts">
import { AlertTriangle, CheckCircle2, Info, X, XCircle } from "@lucide/vue";
import { removeToast, toasts } from "../../lib/notify";

/** 手动关闭一条轻提示。 */
function dismiss(id: number): void {
  removeToast(id);
}

/** 为提示级别选择语义化图标。 */
function iconFor(kind: string): typeof CheckCircle2 {
  if (kind === "success") return CheckCircle2;
  if (kind === "error") return XCircle;
  if (kind === "warning") return AlertTriangle;
  return Info;
}
</script>

<template>
  <div class="toast-host" aria-live="polite" aria-atomic="false">
    <TransitionGroup name="toast-stack" tag="div" class="toast-list">
      <div v-for="item in toasts" :key="item.id" :class="['toast-item', 'toast-item--' + item.kind]" role="status">
        <component :is="iconFor(item.kind)" class="toast-item-icon" :size="18" aria-hidden="true" />
        <span class="toast-item-message">{{ item.message }}</span>
        <button type="button" aria-label="关闭提示" @click="dismiss(item.id)"><X :size="15" /></button>
      </div>
    </TransitionGroup>
  </div>
</template>
