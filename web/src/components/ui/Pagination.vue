<script setup lang="ts">
import { ChevronLeft, ChevronRight } from "@lucide/vue";

const props = defineProps<{ page: number; pageSize: number; total: number }>();
const emit = defineEmits<{ change: [page: number] }>();
const pages = () => Math.max(1, Math.ceil(props.total / props.pageSize));
</script>

<template>
  <nav v-if="pages() > 1" class="ui-pagination" aria-label="分页">
    <button type="button" :disabled="page <= 1" aria-label="上一页" @click="emit('change', page - 1)"><ChevronLeft :size="16" /></button>
    <button v-for="item in pages()" :key="item" type="button" :class="{ active: item === page }" @click="emit('change', item)">{{ item }}</button>
    <button type="button" :disabled="page >= pages()" aria-label="下一页" @click="emit('change', page + 1)"><ChevronRight :size="16" /></button>
  </nav>
</template>
