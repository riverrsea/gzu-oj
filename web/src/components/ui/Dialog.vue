<script setup lang="ts">
import { onBeforeUnmount, watch } from "vue";
import { X } from "@lucide/vue";

const props = withDefaults(defineProps<{
  /** 控制对话框是否显示。 */
  modelValue: boolean;
  /** 对话框标题。 */
  title: string;
  /** 标题下方的可选说明。 */
  description?: string;
  /** 由使用方传入的对话框尺寸或场景样式。 */
  class?: string;
}>(), { description: "" });
const emit = defineEmits<{ "update:modelValue": [value: boolean] }>();

function close(): void { emit("update:modelValue", false); }
function onKeydown(event: KeyboardEvent): void { if (event.key === "Escape") close(); }
watch(() => props.modelValue, (open) => { if (open) window.addEventListener("keydown", onKeydown); else window.removeEventListener("keydown", onKeydown); }, { immediate: true });
onBeforeUnmount(() => window.removeEventListener("keydown", onKeydown));
</script>

<template>
  <Teleport to="body">
    <div v-if="modelValue" class="ui-dialog-backdrop" @click.self="close">
      <section :class="['ui-dialog', props.class]" role="dialog" aria-modal="true" :aria-label="title">
        <header class="ui-dialog-header"><div><h2>{{ title }}</h2><p v-if="description">{{ description }}</p></div><button class="icon-button" type="button" aria-label="关闭" @click="close"><X :size="18" /></button></header>
        <div class="ui-dialog-content"><slot /></div>
        <footer v-if="$slots.footer" class="ui-dialog-footer"><slot name="footer" /></footer>
      </section>
    </div>
  </Teleport>
</template>
