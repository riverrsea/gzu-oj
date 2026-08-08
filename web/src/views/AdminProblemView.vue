<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import { Bot, Plus, Save, Trash2 } from "@lucide/vue";
import { ElMessage } from "element-plus";
import { useRouter } from "vue-router";
import { api } from "../api/client";
import type { CreatedProblemVersion, Difficulty } from "../api/types";
import MarkdownEditor from "../components/MarkdownEditor.vue";

/** 管理员录入的测试点表单。 */
interface TestCaseForm {
  /** 测试输入。 */
  input: string;
  /** 标准输出。 */
  output: string;
  /** 测试点分值。 */
  score: number;
  /** 是否作为公开样例。 */
  sample: boolean;
}

/** 页面路由器。 */
const router = useRouter();
/** 表单提交状态。 */
const saving = ref(false);
/** 最近创建的不可变题目版本。 */
const created = ref<CreatedProblemVersion>();
/** 逗号分隔的标签输入。 */
const tagText = ref("");
/** 单题录入表单。 */
const form = reactive({
  sourceKey: "",
  title: "",
  school: "贵州大学",
  year: new Date().getFullYear(),
  difficulty: "MEDIUM" as Difficulty,
  sourceUrl: "",
  statementMarkdown: "# 题目描述\n\n请在此填写题面。\n",
  timeLimitMs: 1000,
  memoryLimitMiB: 256,
  publish: false,
  dataNotice: "",
  testCases: [{ input: "", output: "", score: 100, sample: true }] as TestCaseForm[],
});

/** 当前测试点总分。 */
const totalScore = computed(() => form.testCases.reduce((sum, item) => sum + Number(item.score || 0), 0));

/** 增加一个默认测试点。 */
function addCase(): void {
  form.testCases.push({ input: "", output: "", score: 0, sample: false });
}

/** 删除指定测试点，并确保至少保留一个。 */
function removeCase(index: number): void {
  if (form.testCases.length === 1) {
    ElMessage.warning("至少需要一个测试点");
    return;
  }
  form.testCases.splice(index, 1);
}

/** 校验并创建题目草稿或发布版本。 */
async function save(): Promise<void> {
  if (form.publish && totalScore.value !== 100) {
    ElMessage.error("立即发布时测试点分值之和必须为 100");
    return;
  }
  saving.value = true;
  try {
    created.value = await api.createProblem({
      ...form,
      sourceUrl: form.sourceUrl.trim() || null,
      dataNotice: form.dataNotice.trim() || null,
      tags: tagText.value.split(/[，,]/).map((tag) => tag.trim()).filter(Boolean),
    });
    ElMessage.success(form.publish ? "题目版本已发布" : "题目草稿已创建");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "题目保存失败");
  } finally {
    saving.value = false;
  }
}

/** 前往 AI 录题页面并携带刚创建的草稿版本标识。 */
function openAi(): void {
  if (!created.value || created.value.status !== "DRAFT") return;
  void router.push({ path: "/admin/ai", query: { versionId: created.value.versionId } });
}
</script>

<template>
  <section class="content-page admin-problem-page">
    <div class="page-heading">
      <div><h1>单题录入</h1><p>保存后形成不可变版本；修改已发布题目时请使用相同来源键创建新版本</p></div>
    </div>

    <el-form label-position="top" class="problem-form" @submit.prevent="save">
      <section class="form-section">
        <h2>题目元数据</h2>
        <div class="form-grid form-grid--three">
          <el-form-item label="来源键"><el-input v-model="form.sourceKey" maxlength="128" placeholder="gzu-2025-001" /></el-form-item>
          <el-form-item label="学校"><el-input v-model="form.school" maxlength="200" /></el-form-item>
          <el-form-item label="年份"><el-input-number v-model="form.year" :min="1900" :max="2200" /></el-form-item>
        </div>
        <el-form-item label="标题"><el-input v-model="form.title" maxlength="200" /></el-form-item>
        <div class="form-grid form-grid--three">
          <el-form-item label="难度"><el-select v-model="form.difficulty"><el-option label="基础" value="EASY" /><el-option label="综合" value="MEDIUM" /><el-option label="高难" value="HARD" /></el-select></el-form-item>
          <el-form-item label="标签（逗号分隔）"><el-input v-model="tagText" placeholder="动态规划, 图论" /></el-form-item>
          <el-form-item label="来源链接"><el-input v-model="form.sourceUrl" placeholder="https://..." /></el-form-item>
        </div>
      </section>

      <section class="form-section">
        <h2>题面与限制</h2>
        <el-form-item label="Markdown 题面" class="markdown-form-item"><MarkdownEditor v-model="form.statementMarkdown" /></el-form-item>
        <div class="form-grid form-grid--three">
          <el-form-item label="基准时间限制（ms）"><el-input-number v-model="form.timeLimitMs" :min="100" :max="60000" :step="100" /></el-form-item>
          <el-form-item label="基准内存限制（MiB）"><el-input-number v-model="form.memoryLimitMiB" :min="16" :max="2048" :step="16" /></el-form-item>
          <el-form-item label="数据声明"><el-input v-model="form.dataNotice" maxlength="200" placeholder="AI 数据请注明非官方" /></el-form-item>
        </div>
      </section>

      <section class="form-section">
        <header class="section-heading"><div><h2>测试点</h2><p :class="{ 'score-invalid': totalScore !== 100 }">总分 {{ totalScore }} / 100</p></div><el-button @click="addCase"><Plus :size="16" />添加测试点</el-button></header>
        <div class="test-case-editor">
          <article v-for="(item, index) in form.testCases" :key="index" class="test-case-card">
            <header><strong>测试点 {{ index + 1 }}</strong><button class="icon-button" type="button" title="删除测试点" @click="removeCase(index)"><Trash2 :size="17" /></button></header>
            <div class="test-case-columns"><el-form-item label="输入"><el-input v-model="item.input" type="textarea" :rows="5" /></el-form-item><el-form-item label="标准输出"><el-input v-model="item.output" type="textarea" :rows="5" /></el-form-item></div>
            <footer><el-checkbox v-model="item.sample">公开样例</el-checkbox><el-form-item label="分值"><el-input-number v-model="item.score" :min="0" :max="100" /></el-form-item></footer>
          </article>
        </div>
      </section>

      <footer class="form-actions">
        <el-checkbox v-model="form.publish">校验通过后立即发布</el-checkbox>
        <el-button type="primary" native-type="submit" :loading="saving"><Save :size="16" />保存版本</el-button>
      </footer>
    </el-form>

    <section v-if="created" class="created-result">
      <div><strong>版本 {{ created.versionNumber }} 已创建</strong><p>{{ created.versionId }} · {{ created.status }}</p></div>
      <el-button v-if="created.status === 'DRAFT'" @click="openAi"><Bot :size="16" />前往 AI 录题</el-button>
    </section>
  </section>
</template>
