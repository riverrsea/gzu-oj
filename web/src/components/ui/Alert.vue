<script setup lang="ts">
import { computed } from "vue";
import { cn } from "../../lib/utils";

const props = withDefaults(defineProps<{ title?: string; variant?: "default" | "info" | "success" | "warning" | "error"; class?: string }>(), { variant: "default" });
const classes = computed(() => cn("ui-alert", "ui-alert--" + props.variant, props.class));
</script>

<template>
  <div :class="classes" role="status">
    <strong v-if="title">{{ title }}</strong>
    <div v-if="$slots.default"><slot /></div>
  </div>
</template>
