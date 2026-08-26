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
  "ui-button",
  props.variant === "default" && "ui-button--default",
  props.variant === "outline" && "ui-button--outline",
  props.variant === "ghost" && "ui-button--ghost",
  props.variant === "destructive" && "ui-button--destructive",
  props.variant === "link" && "ui-button--link",
  props.size === "default" && "ui-button--size-default",
  props.size === "sm" && "ui-button--size-sm",
  props.size === "lg" && "ui-button--size-lg",
  props.size === "icon" && "ui-button--size-icon",
  props.class,
));
</script>

<template>
  <button :type="type" :class="classes" :disabled="disabled || loading">
    <span v-if="loading" class="ui-button-spinner" aria-hidden="true" />
    <slot v-else />
  </button>
</template>
