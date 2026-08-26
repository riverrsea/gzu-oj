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
const classes = computed(() => cn("ui-control", props.class));
</script>

<template>
  <input :value="modelValue ?? ''" type="number" :min="min" :max="max" :placeholder="placeholder" :disabled="disabled" :class="classes" @input="emit('update:modelValue', ($event.target as HTMLInputElement).value === '' ? undefined : Number(($event.target as HTMLInputElement).value))" />
</template>
