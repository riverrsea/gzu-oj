<script setup lang="ts">
import { computed } from "vue";
import { cn } from "../../lib/utils";
const props = withDefaults(defineProps<{ modelValue?: string; rows?: number; placeholder?: string; disabled?: boolean; class?: string }>(), { modelValue: "", rows: 4 });
const emit = defineEmits<{ "update:modelValue": [value: string] }>();
const classes = computed(() => cn("min-h-20 w-full resize-y rounded-md border border-line bg-paper px-3 py-2 text-sm text-ink shadow-sm outline-none placeholder:text-quiet focus-visible:border-brand focus-visible:ring-2 focus-visible:ring-brand/20 disabled:cursor-not-allowed disabled:opacity-50", props.class));
</script>

<template><textarea :value="modelValue" :rows="rows" :placeholder="placeholder" :disabled="disabled" :class="classes" @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)" /></template>
