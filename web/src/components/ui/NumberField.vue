<script setup lang="ts">
import { computed } from "vue";
import { cn } from "../../lib/utils";

const props = withDefaults(defineProps<{
  modelValue?: number;
  min?: number;
  max?: number;
  placeholder?: string;
  disabled?: boolean;
  class?: string;
}>(), { modelValue: undefined });
const emit = defineEmits<{ "update:modelValue": [value: number | undefined] }>();
const classes = computed(() => cn("flex h-10 w-full rounded-md border border-line bg-paper px-3 py-2 text-sm text-ink shadow-sm outline-none transition-colors placeholder:text-quiet focus-visible:border-brand focus-visible:ring-2 focus-visible:ring-brand/20 disabled:cursor-not-allowed disabled:opacity-50", props.class));
</script>

<template>
  <input :value="modelValue ?? ''" type="number" :min="min" :max="max" :placeholder="placeholder" :disabled="disabled" :class="classes" @input="emit('update:modelValue', ($event.target as HTMLInputElement).value === '' ? undefined : Number(($event.target as HTMLInputElement).value))" />
</template>
