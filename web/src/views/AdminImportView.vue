<script setup lang="ts">
import { computed, ref } from "vue";
import { ClipboardCheck, FileArchive, FileCheck2, Upload } from "@lucide/vue";
import { toast } from "../lib/notify";
import { api } from "../api/client";
import type { ImportBatch } from "../api/types";
import UiButton from "../components/ui/Button.vue";

/** 待上传的标准导入包。 */
const file = ref<File>();
/** 是否有文件正被拖入上传区。 */
const dragover = ref(false);
/** ZIP 上传和校验状态。 */
const staging = ref(false);
/** 批次提交状态。 */
const committing = ref(false);
/** 最近一次导入预览。 */
const batch = ref<ImportBatch>();

/** 批次中校验通过的条目数量。 */
const validCount = computed(() => batch.value?.items.filter((item) => item.status === "VALID").length ?? 0);
/** 批次是否包含阻止提交的错误项。 */
const hasInvalidItems = computed(() => batch.value?.items.some((item) => item.status === "INVALID") ?? false);

/** 批次状态显示文本。 */
function batchStatusLabel(status: string): string {
  return { STAGED: "已校验", VALIDATED: "已校验", IMPORTED: "已导入" }[status] ?? status;
}

/** 记录通过文件选择器选中的 ZIP。 */
function selectFile(event: Event): void {
  file.value = (event.target as HTMLInputElement).files?.[0];
}

/** 记录拖入上传区的 ZIP 文件。 */
function dropFile(event: DragEvent): void {
  dragover.value = false;
  const dropped = event.dataTransfer?.files?.[0];
  if (!dropped) return;
  if (!dropped.name.toLowerCase().endsWith(".zip")) {
    toast.warning("仅支持 .zip 标准导入包");
    return;
  }
  file.value = dropped;
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
  <section class="admin-page admin-page--narrow">
    <div class="admin-page-head">
      <div>
        <h1>批量导入</h1>
      </div>
    </div>

    <section class="admin-panel">
      <header class="admin-panel-head">
        <span class="admin-panel-icon"><FileArchive :size="17" /></span>
        <div class="admin-panel-titles"><h2>标准 ZIP 导入</h2></div>
      </header>
      <div class="admin-panel-body">
        <label
          class="admin-dropzone"
          :class="{ 'admin-dropzone--dragover': dragover }"
          @dragover.prevent="dragover = true"
          @dragleave.prevent="dragover = false"
          @drop.prevent="dropFile"
        >
          <input type="file" accept=".zip,application/zip" @change="selectFile" />
          <span class="admin-dropzone-icon"><Upload :size="19" /></span>
          <span class="admin-dropzone-text">
            <strong>{{ file?.name ?? "点击选择或拖入 ZIP 文件" }}</strong>
            <span>{{ file ? "已选择导入包，可开始校验预览" : "仅支持 .zip 标准导入包" }}</span>
          </span>
        </label>
        <div class="admin-filters-actions">
          <UiButton :loading="staging" :disabled="!file" @click="stageImport"><FileCheck2 :size="16" />校验预览</UiButton>
        </div>
      </div>
    </section>

    <section v-if="batch" class="admin-panel admin-table-card">
      <header class="admin-panel-head">
        <span class="admin-panel-icon"><ClipboardCheck :size="17" /></span>
        <div class="admin-panel-titles"><h2>批次预览</h2><p class="admin-cell-key">{{ batch.id }}</p></div>
        <div class="admin-panel-head-actions">
          <span :class="['admin-status', 'admin-status--' + batch.status.toLowerCase()]">{{ batchStatusLabel(batch.status) }}</span>
        </div>
      </header>
      <div class="admin-panel-body">
        <div class="admin-batch-stats">
          <span class="admin-status admin-status--valid">有效 {{ validCount }}</span>
          <span v-if="hasInvalidItems" class="admin-status admin-status--invalid">无效 {{ batch.items.length - validCount }}</span>
        </div>
      </div>
      <div class="admin-table-scroll">
        <table class="admin-table">
          <thead><tr><th>外部题目标识</th><th>标题</th><th>测点</th><th>状态</th><th>错误</th></tr></thead>
          <tbody>
            <tr v-for="row in batch.items" :key="row.externalKey">
              <td><span class="admin-cell-key">{{ row.externalKey }}</span></td>
              <td>{{ row.title }}</td>
              <td>{{ row.testCaseCount }} 个</td>
              <td><span :class="['admin-status', 'admin-status--' + row.status.toLowerCase()]">{{ row.status === "VALID" ? "有效" : "无效" }}</span></td>
              <td>{{ row.errors.join("；") || "—" }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer class="admin-panel-foot">
        <UiButton :loading="committing" :disabled="hasInvalidItems || batch.status !== 'VALIDATED'" @click="commitImport">提交导入</UiButton>
      </footer>
    </section>
  </section>
</template>
