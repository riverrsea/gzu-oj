<script setup lang="ts">
import { computed } from "vue";
import { cn } from "../../lib/utils";
const props = defineProps<{ class?: string }>();
const classes = computed(() => cn("w-full caption-bottom text-sm", props.class));
</script>

<template>
  <div class="w-full overflow-x-auto rounded-lg border border-line bg-paper shadow-panel">
    <table :class="classes"><slot /></table>
  </div>
</template>
