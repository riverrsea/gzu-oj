<script setup lang="ts">
import { computed } from "vue";
import { cn } from "../../lib/utils";

type ButtonVariant = "default" | "outline" | "ghost" | "destructive" | "link";
type ButtonSize = "default" | "sm" | "lg" | "icon";

const props = withDefaults(defineProps<{
  variant?: ButtonVariant;
  size?: ButtonSize;
  loading?: boolean;
  disabled?: boolean;
  type?: "button" | "submit" | "reset";
  class?: string;
}>(), {
  variant: "default",
  size: "default",
  loading: false,
  disabled: false,
  type: "button",
});

const classes = computed(() => cn(
  "inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-md text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand disabled:pointer-events-none disabled:opacity-50",
  props.variant === "default" && "bg-brand text-white hover:bg-brand-strong",
  props.variant === "outline" && "border border-line bg-paper text-ink hover:bg-canvas",
  props.variant === "ghost" && "text-quiet hover:bg-canvas hover:text-ink",
  props.variant === "destructive" && "bg-danger text-white hover:opacity-90",
  props.variant === "link" && "text-brand underline-offset-4 hover:underline",
  props.size === "default" && "h-10 px-4 py-2",
  props.size === "sm" && "h-9 rounded-md px-3 text-xs",
  props.size === "lg" && "h-11 rounded-md px-8",
  props.size === "icon" && "h-10 w-10",
  props.class,
));
</script>

<template>
  <button :type="type" :class="classes" :disabled="disabled || loading">
    <span v-if="loading" class="h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent" aria-hidden="true" />
    <slot v-else />
  </button>
</template>
