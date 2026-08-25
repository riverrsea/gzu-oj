<script setup lang="ts">
import { onBeforeUnmount, watch } from "vue";
import { X } from "@lucide/vue";

const props = withDefaults(defineProps<{ modelValue: boolean; title: string; description?: string }>(), { description: "" });
const emit = defineEmits<{ "update:modelValue": [value: boolean] }>();

function close(): void { emit("update:modelValue", false); }
function onKeydown(event: KeyboardEvent): void { if (event.key === "Escape") close(); }
watch(() => props.modelValue, (open) => { if (open) window.addEventListener("keydown", onKeydown); else window.removeEventListener("keydown", onKeydown); }, { immediate: true });
onBeforeUnmount(() => window.removeEventListener("keydown", onKeydown));
</script>

<template>
  <Teleport to="body">
    <div v-if="modelValue" class="ui-dialog-backdrop" @click.self="close">
      <section class="ui-dialog" role="dialog" aria-modal="true" :aria-label="title">
        <header class="ui-dialog-header"><div><h2>{{ title }}</h2><p v-if="description">{{ description }}</p></div><button class="icon-button" type="button" aria-label="关闭" @click="close"><X :size="18" /></button></header>
        <div class="ui-dialog-content"><slot /></div>
        <footer v-if="$slots.footer" class="ui-dialog-footer"><slot name="footer" /></footer>
      </section>
    </div>
  </Teleport>
</template>
