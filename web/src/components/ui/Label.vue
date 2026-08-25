<script setup lang="ts">
import { computed } from "vue";
import { cn } from "../../lib/utils";

const props = defineProps<{ for?: string; class?: string }>();
const classes = computed(() => cn("text-sm font-medium leading-none text-ink", props.class));
</script>

<template><label :for="props.for" :class="classes"><slot /></label></template>
