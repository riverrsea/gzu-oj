<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ArrowLeft, Bot, Plus, Save, Send, Trash2 } from "@lucide/vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "../api/client";
import type { AdminProblemVersionDetail, Difficulty } from "../api/types";
import MarkdownEditor from "../components/MarkdownEditor.vue";

interface TestCaseForm {
  input: string;
  output: string;
  score: number;
  sample: boolean;
}

const route = useRoute();
const router = useRouter();
const detail = ref<AdminProblemVersionDetail>();
const loading = ref(true);
const saving = ref(false);
const tagText = ref("");
const form = reactive({
  title: "",
  school: "",
  year: new Date().getFullYear(),
  difficulty: "MEDIUM" as Difficulty,
  sourceUrl: "",
  statementMarkdown: "",
  timeLimitMs: 1000,
  memoryLimitMiB: 256,
  dataNotice: "",
  testCases: [] as TestCaseForm[],
});

const totalScore = computed(() => form.testCases.reduce((sum, item) => sum + Number(item.score || 0), 0));

function addCase(): void {
  form.testCases.push({ input: "", output: "", score: 0, sample: false });
}

function removeCase(index: number): void {
  if (form.testCases.length === 1) {
    ElMessage.warning("至少需要一个测试点");
    return;
  }
  form.testCases.splice(index, 1);
}

async function load(): Promise<void> {
  try {
    detail.value = await api.adminProblemVersion(String(route.params.versionId));
    if (detail.value.status !== "DRAFT") {
      ElMessage.warning("只有草稿版本可以编辑");
      await router.replace("/admin/problems");
      return;
    }
    form.title = detail.value.title;
    form.school = detail.value.school;
    form.year = detail.value.year;
    form.difficulty = detail.value.difficulty;
    form.sourceUrl = detail.value.sourceUrl ?? "";
    form.statementMarkdown = detail.value.statementMarkdown;
    form.timeLimitMs = detail.value.timeLimitMs;
    form.memoryLimitMiB = detail.value.memoryLimitMiB;
    form.dataNotice = detail.value.dataNotice ?? "";
    tagText.value = detail.value.tags.join(", ");
    form.testCases = detail.value.testCases.map(({ input, output, score, sample }) => ({ input, output, score, sample }));
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "草稿加载失败");
    await router.replace("/admin/problems");
  } finally {
    loading.value = false;
  }
}

async function save(publish: boolean): Promise<void> {
  if (!detail.value || saving.value || detail.value.activeAiRun) return;
  if (publish && totalScore.value !== 100) {
    ElMessage.error("发布时测试点分值之和必须为 100");
    return;
  }
  if (publish) {
    try {
      await ElMessageBox.confirm("发布后该版本将不可再编辑，并会成为这道题的当前公开版本。", "确认发布", { type: "warning", confirmButtonText: "发布", cancelButtonText: "取消" });
    } catch {
      return;
    }
  }
  saving.value = true;
  try {
    await api.updateDraftProblem(detail.value.versionId, {
      expectedContentSha256: detail.value.contentSha256,
      ...form,
      sourceUrl: form.sourceUrl.trim() || null,
      dataNotice: form.dataNotice.trim() || null,
      tags: tagText.value.split(/[，,]/).map((tag) => tag.trim()).filter(Boolean),
      publish,
    });
    ElMessage.success(publish ? "题目版本已发布" : "草稿已保存");
    if (publish) {
      await router.replace("/admin/problems");
    } else {
      await load();
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "草稿保存失败");
  } finally {
    saving.value = false;
  }
}

function openAi(): void {
  if (!detail.value) return;
  void router.push({ path: "/admin/ai", query: { versionId: detail.value.versionId } });
}

onMounted(() => void load());
</script>

<template>
  <section v-loading="loading" class="content-page admin-problem-page">
    <div class="page-heading">
      <div><h1>编辑题目草稿</h1><p>版本 v{{ detail?.versionNumber }} · 外部题目标识 {{ detail?.externalKey || '手工题目' }}（只读）</p></div>
      <el-button text @click="router.push('/admin/problems')"><ArrowLeft :size="16" />返回题库</el-button>
    </div>

    <el-alert v-if="detail?.activeAiRun" type="warning" show-icon title="该草稿存在进行中的 AI 流程，请先取消 AI 流程后再编辑。" />
    <el-form label-position="top" class="problem-form" @submit.prevent="save(false)">
      <section class="form-section">
        <h2>题目元数据</h2>
        <div class="form-grid form-grid--three">
          <el-form-item label="外部题目标识"><el-input :model-value="detail?.externalKey || '手工题目'" disabled /></el-form-item>
          <el-form-item label="学校"><el-input v-model="form.school" maxlength="200" :disabled="Boolean(detail?.activeAiRun)" /></el-form-item>
          <el-form-item label="年份"><el-input-number v-model="form.year" :min="1900" :max="2200" :disabled="Boolean(detail?.activeAiRun)" /></el-form-item>
        </div>
        <el-form-item label="标题"><el-input v-model="form.title" maxlength="200" :disabled="Boolean(detail?.activeAiRun)" /></el-form-item>
        <div class="form-grid form-grid--three">
          <el-form-item label="难度"><el-select v-model="form.difficulty" :disabled="Boolean(detail?.activeAiRun)"><el-option label="基础" value="EASY" /><el-option label="综合" value="MEDIUM" /><el-option label="高难" value="HARD" /></el-select></el-form-item>
          <el-form-item label="标签（逗号分隔）"><el-input v-model="tagText" :disabled="Boolean(detail?.activeAiRun)" /></el-form-item>
          <el-form-item label="来源链接"><el-input v-model="form.sourceUrl" placeholder="https://..." :disabled="Boolean(detail?.activeAiRun)" /></el-form-item>
        </div>
      </section>

      <section class="form-section">
        <h2>题面与限制</h2>
        <el-form-item label="Markdown 题面" class="markdown-form-item"><MarkdownEditor v-model="form.statementMarkdown" /></el-form-item>
        <div class="form-grid form-grid--three">
          <el-form-item label="基准时间限制（ms）"><el-input-number v-model="form.timeLimitMs" :min="100" :max="60000" :step="100" :disabled="Boolean(detail?.activeAiRun)" /></el-form-item>
          <el-form-item label="基准内存限制（MiB）"><el-input-number v-model="form.memoryLimitMiB" :min="16" :max="2048" :step="16" :disabled="Boolean(detail?.activeAiRun)" /></el-form-item>
          <el-form-item label="数据声明"><el-input v-model="form.dataNotice" maxlength="200" :disabled="Boolean(detail?.activeAiRun)" /></el-form-item>
        </div>
      </section>

      <section class="form-section">
        <header class="section-heading"><div><h2>测试点</h2><p :class="{ 'score-invalid': totalScore !== 100 }">总分 {{ totalScore }} / 100</p></div><el-button :disabled="Boolean(detail?.activeAiRun)" @click="addCase"><Plus :size="16" />添加测试点</el-button></header>
        <div class="test-case-editor">
          <article v-for="(item, index) in form.testCases" :key="index" class="test-case-card">
            <header><strong>测试点 {{ index + 1 }}</strong><button class="icon-button" type="button" title="删除测试点" :disabled="Boolean(detail?.activeAiRun)" @click="removeCase(index)"><Trash2 :size="17" /></button></header>
            <div class="test-case-columns"><el-form-item label="输入"><el-input v-model="item.input" type="textarea" :rows="5" :disabled="Boolean(detail?.activeAiRun)" /></el-form-item><el-form-item label="标准输出"><el-input v-model="item.output" type="textarea" :rows="5" :disabled="Boolean(detail?.activeAiRun)" /></el-form-item></div>
            <footer><el-checkbox v-model="item.sample" :disabled="Boolean(detail?.activeAiRun)">公开样例</el-checkbox><el-form-item label="分值"><el-input-number v-model="item.score" :min="0" :max="100" :disabled="Boolean(detail?.activeAiRun)" /></el-form-item></footer>
          </article>
        </div>
      </section>

      <footer class="form-actions">
        <el-button v-if="detail?.activeAiRun" @click="openAi"><Bot :size="16" />查看 AI 流程</el-button>
        <el-button :disabled="Boolean(detail?.activeAiRun)" :loading="saving" @click="save(false)"><Save :size="16" />保存草稿</el-button>
        <el-button type="primary" :disabled="Boolean(detail?.activeAiRun)" :loading="saving" @click="save(true)"><Send :size="16" />保存并发布</el-button>
      </footer>
    </el-form>
  </section>
</template>
