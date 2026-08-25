<script setup lang="ts">
import { computed } from "vue";
import { cn } from "../../lib/utils";

const props = withDefaults(defineProps<{ modelValue?: boolean; disabled?: boolean; class?: string }>(), { modelValue: false });
const emit = defineEmits<{ "update:modelValue": [value: boolean] }>();
const classes = computed(() => cn("h-4 w-4 rounded border-line accent-brand focus:ring-brand", props.class));
</script>

<template><input type="checkbox" :checked="modelValue" :disabled="disabled" :class="classes" @change="emit('update:modelValue', ($event.target as HTMLInputElement).checked)" /></template>
