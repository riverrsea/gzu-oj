<script setup lang="ts">
import { computed } from "vue";
import { cn } from "../../lib/utils";

const props = withDefaults(defineProps<{
  modelValue?: string;
  placeholder?: string;
  disabled?: boolean;
  class?: string;
}>(), { modelValue: "", placeholder: "请选择" });
const emit = defineEmits<{ "update:modelValue": [value: string] }>();
const classes = computed(() => cn("h-10 w-full rounded-md border border-line bg-paper px-3 text-sm text-ink outline-none transition-colors focus:border-brand focus:ring-2 focus:ring-brand/20 disabled:cursor-not-allowed disabled:opacity-50", props.class));
</script>

<template>
  <select :value="modelValue" :disabled="disabled" :class="classes" @change="emit('update:modelValue', ($event.target as HTMLSelectElement).value)">
    <option v-if="placeholder" value="">{{ placeholder }}</option>
    <slot />
  </select>
</template>
