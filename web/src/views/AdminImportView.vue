<script setup lang="ts">
import { computed, ref } from "vue";
import { FileArchive, Upload } from "@lucide/vue";
import { ElMessage } from "element-plus";
import { api } from "../api/client";
import type { ImportBatch } from "../api/types";

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
    ElMessage.warning("请先选择标准导入 ZIP");
    return;
  }
  staging.value = true;
  try {
    batch.value = await api.stageImport(file.value);
    ElMessage.success("导入包校验完成");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "导入包校验失败");
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
    ElMessage.success(`已导入 ${result.imported} 题，跳过 ${result.skipped} 题，失败 ${result.invalid} 题`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "批次提交失败");
  } finally {
    committing.value = false;
  }
}
</script>

<template>
  <section class="content-page admin-page">
    <div class="page-heading"><div><h1>批量导入</h1><p>上传标准 ZIP，先完成安全校验和预览，再写入题目草稿</p></div></div>
    <section class="admin-tool-surface">
      <header><FileArchive :size="21" /><div><h2>标准 ZIP 导入</h2><p>服务端检查编码、目录穿越、压缩炸弹、重复外部题目标识、题面与测试数据。</p></div></header>
      <div class="upload-row"><label class="upload-command"><Upload :size="18" />选择 ZIP<input type="file" accept=".zip,application/zip" @change="selectFile" /></label><span>{{ file?.name ?? '尚未选择文件' }}</span><el-button :loading="staging" :disabled="!file" @click="stageImport">校验预览</el-button></div>
      <template v-if="batch">
        <el-table :data="batch.items" size="small" row-key="externalKey"><el-table-column prop="externalKey" label="外部题目标识" min-width="150" /><el-table-column prop="title" label="标题" min-width="180" /><el-table-column prop="testCaseCount" label="测点" width="70" /><el-table-column prop="status" label="状态" width="90" /><el-table-column label="错误" min-width="190"><template #default="{ row }">{{ row.errors.join('；') || '—' }}</template></el-table-column></el-table>
        <div class="tool-actions"><span>批次 {{ batch.id }} · {{ batch.status }}</span><el-button type="primary" :loading="committing" :disabled="hasInvalidItems || batch.status !== 'VALIDATED'" @click="commitImport">提交导入</el-button></div>
      </template>
    </section>
  </section>
</template>
