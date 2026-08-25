<script setup lang="ts">
import { computed } from "vue";
import { cn } from "../../lib/utils";
const props = withDefaults(defineProps<{ description?: string; class?: string }>(), { description: "暂无数据" });
const classes = computed(() => cn("flex min-h-40 flex-col items-center justify-center gap-2 px-4 text-sm text-quiet", props.class));
</script>

<template><div :class="classes"><slot name="icon" /><p>{{ description }}</p><slot /></div></template>
