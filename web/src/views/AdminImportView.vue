<script setup lang="ts">
import { computed, ref } from "vue";
import { FileArchive, Upload } from "@lucide/vue";
import { toast } from "../lib/notify";
import { api } from "../api/client";
import type { ImportBatch } from "../api/types";
import UiButton from "../components/ui/Button.vue";
import UiTable from "../components/ui/Table.vue";

/** 待上传的标准导入包。 */
const file = ref<File>();
/** ZIP 上传和校验状态。 */
const staging = ref(false);
/** 批次提交状态。 */
const committing = ref(false);
/** 最近一次导入预览。 */
const batch = ref<ImportBatch>();

/** 批次是否包含阻止提交的错误项。 */
const hasInvalidItems = computed(() => batch.value?.items.some((item) => item.status === "INVALID") ?? false);

/** 记录文件选择。 */
function selectFile(event: Event): void {
  file.value = (event.target as HTMLInputElement).files?.[0];
}

/** 上传 ZIP 并展示服务端安全校验预览。 */
async function stageImport(): Promise<void> {
  if (!file.value) {
    toast.warning("请先选择标准导入 ZIP");
    return;
  }
  staging.value = true;
  try {
    batch.value = await api.stageImport(file.value);
    toast.success("导入包校验完成");
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "导入包校验失败");
  } finally {
    staging.value = false;
  }
}

/** 提交已预览批次并创建题目草稿版本。 */
async function commitImport(): Promise<void> {
  if (!batch.value) return;
  committing.value = true;
  try {
    const result = await api.commitImport(batch.value.id);
    batch.value.status = "IMPORTED";
    toast.success(`已导入 ${result.imported} 题，跳过 ${result.skipped} 题，失败 ${result.invalid} 题`);
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "批次提交失败");
  } finally {
    committing.value = false;
  }
}
</script>

<template>
  <section class="content-page content-page--modern oj-page admin-page">
    <div class="page-heading"><h1>批量导入</h1></div>
    <section class="admin-tool-surface">
      <header><FileArchive :size="21" /><div><h2>标准 ZIP 导入</h2></div></header>
      <div class="upload-row"><label class="upload-command"><Upload :size="18" />选择 ZIP<input type="file" accept=".zip,application/zip" @change="selectFile" /></label><span>{{ file?.name ?? '尚未选择文件' }}</span><UiButton :loading="staging" :disabled="!file" @click="stageImport">校验预览</UiButton></div>
      <template v-if="batch">
        <UiTable>
          <thead><tr><th>外部题目标识</th><th>标题</th><th>测点</th><th>状态</th><th>错误</th></tr></thead>
          <tbody><tr v-for="row in batch.items" :key="row.externalKey"><td>{{ row.externalKey }}</td><td>{{ row.title }}</td><td>{{ row.testCaseCount }}</td><td>{{ row.status }}</td><td>{{ row.errors.join('；') || '—' }}</td></tr></tbody>
        </UiTable>
        <div class="tool-actions"><span>批次 {{ batch.id }} · {{ batch.status }}</span><UiButton :loading="committing" :disabled="hasInvalidItems || batch.status !== 'VALIDATED'" @click="commitImport">提交导入</UiButton></div>
      </template>
    </section>
  </section>
</template>
