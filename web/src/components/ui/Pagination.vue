<script setup lang="ts">
import { computed } from "vue";
import { ChevronLeft, ChevronRight } from "@lucide/vue";

const props = defineProps<{ page: number; pageSize: number; total: number }>();
const emit = defineEmits<{ change: [page: number] }>();

/** 总页数，至少保留一页。 */
const pages = computed(() => Math.max(1, Math.ceil(props.total / props.pageSize)));

/** 窗口化页码：首尾与当前页附近各保留一页，其余区间折叠为省略号。 */
const items = computed<(number | "ellipsis")[]>(() => {
  const totalPages = pages.value;
  const current = props.page;
  if (totalPages <= 7) return Array.from({ length: totalPages }, (_, index) => index + 1);
  const visible = [1, totalPages, current - 1, current, current + 1]
    .filter((item) => item >= 1 && item <= totalPages);
  const sorted = [...new Set(visible)].sort((a, b) => a - b);
  const result: (number | "ellipsis")[] = [];
  let previous = 0;
  for (const item of sorted) {
    if (item - previous > 1) result.push("ellipsis");
    result.push(item);
    previous = item;
  }
  return result;
});
</script>

<template>
  <nav v-if="pages > 1" class="ui-pagination" aria-label="分页">
    <button type="button" :disabled="page <= 1" aria-label="上一页" @click="emit('change', page - 1)"><ChevronLeft :size="16" /></button>
    <template v-for="(item, index) in items" :key="index">
      <span v-if="item === 'ellipsis'" class="ui-pagination-ellipsis" aria-hidden="true">…</span>
      <button v-else type="button" :class="{ active: item === page }" :aria-current="item === page ? 'page' : undefined" @click="emit('change', item)">{{ item }}</button>
    </template>
    <button type="button" :disabled="page >= pages" aria-label="下一页" @click="emit('change', page + 1)"><ChevronRight :size="16" /></button>
  </nav>
</template>
