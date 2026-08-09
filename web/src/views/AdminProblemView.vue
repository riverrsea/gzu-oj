<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { Save } from "@lucide/vue";
import { ElMessage } from "element-plus";
import { useRoute, useRouter } from "vue-router";
import { api } from "../api/client";
import type { AdminProblemVersionDetail, Difficulty } from "../api/types";
import ProblemStatementEditor from "../components/ProblemStatementEditor.vue";

/** 页面路由器。 */
const router = useRouter();
/** 当前路由，用于读取被复制的基础版本。 */
const route = useRoute();
/** 页面初始数据加载状态。 */
const loading = ref(false);
/** 被复制版本的完整信息；为空时表示创建全新逻辑题目。 */
const baseVersion = ref<AdminProblemVersionDetail>();
/** 第一阶段草稿保存状态。 */
const saving = ref(false);
/** 逗号分隔的标签输入。 */
const tagText = ref("");
/** 单题录入表单。 */
const form = reactive({
  externalKey: "",
  title: "",
  school: "贵州大学",
  year: new Date().getFullYear(),
  difficulty: "MEDIUM" as Difficulty,
  sourceUrl: "",
  statementMarkdown: "## 题目描述\n\n请填写题目背景、目标和要求。\n\n## 输入格式\n\n\n## 输出格式\n\n\n## 数据范围\n\n",
  timeLimitMs: 1000,
  memoryLimitMiB: 256,
  dataNotice: "",
});

/** 页面是否正在为已有逻辑题目创建新版本。 */
const creatingNextVersion = computed(() => Boolean(baseVersion.value));

/** 从已有版本复制元数据和题面，随后保存为新的草稿版本。 */
async function loadBaseVersion(): Promise<void> {
  if (typeof route.query.fromVersionId !== "string") return;
  loading.value = true;
  try {
    const loaded = await api.adminProblemVersion(route.query.fromVersionId);
    baseVersion.value = loaded;
    form.externalKey = loaded.externalKey ?? "";
    form.title = loaded.title;
    form.school = loaded.school;
    form.year = loaded.year;
    form.difficulty = loaded.difficulty;
    form.sourceUrl = loaded.sourceUrl ?? "";
    form.statementMarkdown = loaded.statementMarkdown;
    form.timeLimitMs = loaded.timeLimitMs;
    form.memoryLimitMiB = loaded.memoryLimitMiB;
    form.dataNotice = loaded.dataNotice ?? "";
    tagText.value = loaded.tags.join(", ");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "基础版本加载失败");
    await router.replace("/admin/problems");
  } finally {
    loading.value = false;
  }
}

/** 第一阶段只创建题目草稿，测试点在草稿编辑页单独保存。 */
async function save(): Promise<void> {
  saving.value = true;
  try {
    const body = {
      ...form,
      externalKey: form.externalKey.trim() || null,
      sourceUrl: form.sourceUrl.trim() || null,
      dataNotice: form.dataNotice.trim() || null,
      tags: tagText.value.split(/[，,]/).map((tag) => tag.trim()).filter(Boolean),
      publish: false,
      testCases: [],
    };
    const created = baseVersion.value
      ? await api.createProblemVersion(baseVersion.value.problemId, body)
      : await api.createProblem(body);
    ElMessage.success("题目草稿已创建，请继续录入测试点");
    await router.replace(`/admin/problems/${created.versionId}/edit`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "题目保存失败");
  } finally {
    saving.value = false;
  }
}

onMounted(() => void loadBaseVersion());
</script>

<template>
  <section v-loading="loading" class="content-page admin-problem-page">
    <div class="page-heading">
      <div><h1>{{ creatingNextVersion ? '新建题目版本草稿' : '新建题目草稿' }}</h1><p>{{ creatingNextVersion ? `复制 v${baseVersion?.versionNumber} 的题面，新草稿仍归属于原题目 ID` : '第一阶段先保存题面和元数据，测试点将在草稿编辑页单独录入' }}</p></div>
    </div>

    <el-form label-position="top" class="problem-form" @submit.prevent="save">
      <section class="form-section">
        <h2>题目元数据</h2>
        <div class="form-grid form-grid--three">
          <el-form-item label="外部题目标识"><el-input v-model="form.externalKey" maxlength="128" placeholder="noobdream:1006（手工题可留空）" :disabled="creatingNextVersion" /></el-form-item>
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
        <el-form-item label="题面内容" class="statement-form-item"><ProblemStatementEditor v-model="form.statementMarkdown" /></el-form-item>
        <div class="form-grid form-grid--three">
          <el-form-item label="基准时间限制（ms）"><el-input-number v-model="form.timeLimitMs" :min="100" :max="60000" :step="100" /></el-form-item>
          <el-form-item label="基准内存限制（MiB）"><el-input-number v-model="form.memoryLimitMiB" :min="16" :max="2048" :step="16" /></el-form-item>
          <el-form-item label="数据声明"><el-input v-model="form.dataNotice" maxlength="200" placeholder="AI 数据请注明非官方" /></el-form-item>
        </div>
      </section>

      <footer class="form-actions">
        <el-button type="primary" native-type="submit" :loading="saving"><Save :size="16" />创建草稿并继续</el-button>
      </footer>
    </el-form>
  </section>
</template>
