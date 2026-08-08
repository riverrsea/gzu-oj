<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { Bot, ExternalLink, FileArchive, Plus, Search, ServerCog, Upload } from "@lucide/vue";
import { ElMessage } from "element-plus";
import { api } from "../api/client";
import type { AdminProblemSummary, AiRun, Difficulty, ImportBatch, ProblemVersionStatus } from "../api/types";

/** 管理员题库的分页大小。 */
const adminProblemPageSize = 20;
/** 管理员题库加载状态。 */
const adminProblemsLoading = ref(false);
/** 当前页的管理员题目版本。 */
const adminProblems = ref<AdminProblemSummary[]>([]);
/** 管理员题目版本总数。 */
const adminProblemTotal = ref(0);
/** 当前管理员题库页码，从一开始计数。 */
const adminProblemPage = ref(1);
/** 管理员题库筛选条件。 */
const adminProblemFilters = reactive<{
  keyword: string;
  school: string;
  year?: number;
  tag: string;
  difficulty?: Difficulty;
  status?: ProblemVersionStatus;
}>({ keyword: "", school: "", year: undefined, tag: "", difficulty: undefined, status: undefined });

/** 难度显示文本。 */
const difficultyText: Record<Difficulty, string> = { EASY: "简单", MEDIUM: "中等", HARD: "困难" };
/** 题目版本状态显示文本。 */
const versionStatusText: Record<ProblemVersionStatus, string> = { DRAFT: "草稿", PUBLISHED: "已发布", WITHDRAWN: "已撤回" };

/** 将后端状态转换为管理员页面显示文本。 */
function versionStatusLabel(status: string): string {
  return versionStatusText[status as ProblemVersionStatus] ?? status;
}

/** 将后端难度转换为管理员页面显示文本。 */
function difficultyLabel(difficulty: string): string {
  return difficultyText[difficulty as Difficulty] ?? difficulty;
}

/** 格式化管理员列表中的时间。 */
function formatAdminDate(value: string): string {
  return new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}

/** 加载管理员可见的题目版本列表。 */
async function loadAdminProblems(resetPage = false): Promise<void> {
  if (resetPage) adminProblemPage.value = 1;
  adminProblemsLoading.value = true;
  try {
    const result = await api.adminProblems({
      ...adminProblemFilters,
      page: adminProblemPage.value - 1,
      size: adminProblemPageSize,
    });
    adminProblems.value = result.items;
    adminProblemTotal.value = result.total;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "管理员题库加载失败");
  } finally {
    adminProblemsLoading.value = false;
  }
}

/** 提交管理员题库筛选条件。 */
function searchAdminProblems(): void {
  void loadAdminProblems(true);
}

/** 切换管理员题库页码。 */
function changeAdminProblemPage(page: number): void {
  adminProblemPage.value = page;
  void loadAdminProblems();
}

/** 待上传的标准导入包。 */
const file = ref<File>();
/** ZIP 上传和校验状态。 */
const staging = ref(false);
/** 批次提交状态。 */
const committing = ref(false);
/** 最近一次导入预览。 */
const batch = ref<ImportBatch>();
/** AI 草稿版本标识。 */
const aiVersionId = ref("");
/** AI 启动状态。 */
const startingAi = ref(false);
/** 最近启动的 AI 运行。 */
const aiRun = ref<AiRun>();

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

/** 为现有草稿版本启动 Spring AI 多角色流程。 */
async function startAi(): Promise<void> {
  if (!aiVersionId.value.trim()) {
    ElMessage.warning("请输入草稿版本 ID");
    return;
  }
  startingAi.value = true;
  try {
    aiRun.value = await api.startAiRun(aiVersionId.value.trim());
    ElMessage.success("AI 流程已启动");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "AI 流程启动失败");
  } finally {
    startingAi.value = false;
  }
}

onMounted(() => {
  void loadAdminProblems();
});
</script>

<template>
  <section class="content-page admin-page">
    <div class="page-heading"><div><h1>管理</h1><p>题目版本、批量导入和可审计的 AI 录题流程</p></div><div class="heading-actions"><el-button plain @click="$router.push('/admin/workers')"><ServerCog :size="16" />判题 Worker</el-button><el-button type="primary" @click="$router.push('/admin/problems/new')"><Plus :size="16" />新建题目</el-button></div></div>

    <article class="tool-card tool-card--wide admin-catalog-card">
      <header><Search :size="21" /><div><h2>题库目录</h2><p>管理员可以查看所有题目版本，包括草稿、已发布版本和历史版本。</p></div><span class="result-count">{{ adminProblemTotal }} 个版本</span></header>
      <form class="admin-catalog-filters" @submit.prevent="searchAdminProblems">
        <el-input v-model="adminProblemFilters.keyword" clearable placeholder="标题或来源键" />
        <el-input v-model="adminProblemFilters.school" clearable placeholder="学校" />
        <el-input-number v-model="adminProblemFilters.year" :min="1900" :max="2200" :controls="false" placeholder="年份" />
        <el-input v-model="adminProblemFilters.tag" clearable placeholder="标签" />
        <el-select v-model="adminProblemFilters.status" clearable placeholder="版本状态"><el-option label="草稿" value="DRAFT" /><el-option label="已发布" value="PUBLISHED" /><el-option label="已撤回" value="WITHDRAWN" /></el-select>
        <el-select v-model="adminProblemFilters.difficulty" clearable placeholder="难度"><el-option label="简单" value="EASY" /><el-option label="中等" value="MEDIUM" /><el-option label="困难" value="HARD" /></el-select>
        <el-button native-type="submit" type="primary" :loading="adminProblemsLoading"><Search :size="16" />筛选</el-button>
      </form>
      <el-table v-loading="adminProblemsLoading" :data="adminProblems" row-key="versionId" class="problem-table admin-catalog-table">
        <el-table-column label="题目" min-width="280"><template #default="{ row }"><div class="problem-title"><strong>{{ row.title }}</strong><span>{{ row.sourceKey }}</span></div></template></el-table-column>
        <el-table-column prop="school" label="学校" min-width="150" />
        <el-table-column prop="year" label="年份" width="78" />
        <el-table-column label="版本" width="78"><template #default="{ row }">v{{ row.versionNumber }}</template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag size="small" :type="row.status === 'PUBLISHED' ? 'success' : row.status === 'DRAFT' ? 'warning' : 'info'">{{ versionStatusLabel(row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="测点" width="110"><template #default="{ row }">{{ row.testCaseCount }} 个 / {{ row.scoreSum }} 分</template></el-table-column>
        <el-table-column label="难度" width="84"><template #default="{ row }"><span :class="['difficulty', 'difficulty--' + row.difficulty.toLowerCase()]">{{ difficultyLabel(row.difficulty) }}</span></template></el-table-column>
        <el-table-column label="创建时间" width="170"><template #default="{ row }">{{ formatAdminDate(row.createdAt) }}</template></el-table-column>
        <el-table-column label="操作" width="110" fixed="right"><template #default="{ row }"><el-button v-if="row.status === 'PUBLISHED'" text type="primary" @click.stop="$router.push('/problems/' + row.problemId)">查看题面<ExternalLink :size="14" /></el-button><span v-else class="muted-action">暂无公开题面</span></template></el-table-column>
      </el-table>
      <el-empty v-if="!adminProblemsLoading && adminProblems.length === 0" description="没有符合条件的题目版本" />
      <div v-if="adminProblemTotal > adminProblemPageSize" class="admin-catalog-pagination"><el-pagination background layout="prev, pager, next" :current-page="adminProblemPage" :page-size="adminProblemPageSize" :total="adminProblemTotal" @current-change="changeAdminProblemPage" /></div>
    </article>

    <div class="admin-grid">
      <article class="tool-card tool-card--wide">
        <header><FileArchive :size="21" /><div><h2>标准 ZIP 导入</h2><p>服务端先检查编码、目录穿越、压缩炸弹、重复来源键、题面与测试数据，再允许提交。</p></div></header>
        <div class="upload-row"><label class="upload-command"><Upload :size="18" />选择 ZIP<input type="file" accept=".zip,application/zip" @change="selectFile" /></label><span>{{ file?.name ?? '尚未选择文件' }}</span><el-button :loading="staging" :disabled="!file" @click="stageImport">校验预览</el-button></div>
        <template v-if="batch">
          <el-table :data="batch.items" size="small" row-key="sourceKey"><el-table-column prop="sourceKey" label="来源键" min-width="150" /><el-table-column prop="title" label="标题" min-width="180" /><el-table-column prop="testCaseCount" label="测点" width="70" /><el-table-column prop="status" label="状态" width="90" /><el-table-column label="错误" min-width="190"><template #default="{ row }">{{ row.errors.join('；') || '—' }}</template></el-table-column></el-table>
          <div class="tool-actions"><span>批次 {{ batch.id }} · {{ batch.status }}</span><el-button type="primary" :loading="committing" :disabled="hasInvalidItems || batch.status !== 'VALIDATED'" @click="commitImport">提交导入</el-button></div>
        </template>
      </article>

      <article class="tool-card">
        <header><Bot :size="21" /><div><h2>Spring AI 录题</h2><p>模型只生成候选方案与输入；标准输出必须由沙箱中的已校验标程计算，并通过确定性发布门禁。</p></div></header>
        <el-input v-model="aiVersionId" placeholder="草稿版本 UUID" />
        <div class="tool-actions"><span v-if="aiRun">{{ aiRun.state }} · 已完成 {{ aiRun.completedRoles.length }} 个角色</span><span v-else>Provider 默认关闭，可通过环境变量启用</span><el-button :loading="startingAi" @click="startAi">启动流程</el-button></div>
      </article>
    </div>
  </section>
</template>
