<script setup lang="ts">
import { computed } from "vue";
import { cn } from "../../lib/utils";
const props = withDefaults(defineProps<{ modelValue?: string; rows?: number; placeholder?: string; disabled?: boolean; class?: string }>(), { modelValue: "", rows: 4 });
const emit = defineEmits<{ "update:modelValue": [value: string] }>();
const classes = computed(() => cn("ui-control ui-control--textarea", props.class));
</script>

<template><textarea :value="modelValue" :rows="rows" :placeholder="placeholder" :disabled="disabled" :class="classes" @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)" /></template>
