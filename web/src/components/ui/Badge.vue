<script setup lang="ts">
import { computed } from "vue";
import { cn } from "../../lib/utils";

const props = withDefaults(defineProps<{ variant?: "default" | "secondary" | "outline" | "success" | "warning"; class?: string }>(), { variant: "default" });
const classes = computed(() => cn(
  "inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-semibold",
  props.variant === "default" && "border-transparent bg-brand text-white",
  props.variant === "secondary" && "border-transparent bg-canvas text-ink",
  props.variant === "outline" && "border-line text-ink",
  props.variant === "success" && "border-transparent bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-200",
  props.variant === "warning" && "border-transparent bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-200",
  props.class,
));
</script>

<template><span :class="classes"><slot /></span></template>
