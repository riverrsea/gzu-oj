<script setup lang="ts">
import { computed } from "vue";
import { cn } from "../../lib/utils";
const props = defineProps<{ class?: string }>();
const classes = computed(() => cn("rounded-lg border border-line bg-paper text-ink shadow-panel", props.class));
</script>

<template><section :class="classes"><slot /></section></template>
