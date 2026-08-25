<script setup lang="ts">
import { X } from "@lucide/vue";
import { toasts } from "../../lib/notify";

/** 手动关闭一条轻提示。 */
function dismiss(id: number): void {
  toasts.value = toasts.value.filter((toast) => toast.id !== id);
}
</script>

<template>
  <div class="toast-host" aria-live="polite" aria-atomic="false">
    <div v-for="item in toasts" :key="item.id" :class="['toast-item', 'toast-item--' + item.kind]" role="status">
      <span>{{ item.message }}</span>
      <button type="button" aria-label="关闭提示" @click="dismiss(item.id)"><X :size="15" /></button>
    </div>
  </div>
</template>
