<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ArrowLeft, BookOpen, FileText, Save } from "@lucide/vue";
import { toast } from "../lib/notify";
import { useRoute, useRouter } from "vue-router";
import { api } from "../api/client";
import type { AdminProblemVersionDetail, Difficulty } from "../api/types";
import ProblemStatementEditor from "../components/ProblemStatementEditor.vue";
import UiButton from "../components/ui/Button.vue";
import UiInput from "../components/ui/Input.vue";
import UiNumberField from "../components/ui/NumberField.vue";
import UiSelect from "../components/ui/Select.vue";
import UiLabel from "../components/ui/Label.vue";

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
    toast.error(error instanceof Error ? error.message : "基础版本加载失败");
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
    toast.success("题目草稿已创建，请继续录入测试点");
    await router.replace(`/admin/problems/${created.versionId}/edit`);
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "题目保存失败");
  } finally {
    saving.value = false;
  }
}

onMounted(() => void loadBaseVersion());
</script>

<template>
  <section class="admin-page admin-page--narrow loading-shell" :aria-busy="loading">
    <div v-if="loading" class="loading-overlay"><span class="loading-spinner" aria-label="加载中" /></div>
    <div class="admin-page-head">
      <div>
        <h1>{{ creatingNextVersion ? '新建题目版本草稿' : '新建题目草稿' }}</h1>
      </div>
      <div class="admin-page-actions">
        <UiButton variant="ghost" @click="router.push('/admin/problems')"><ArrowLeft :size="16" />返回题库</UiButton>
      </div>
    </div>

    <form class="admin-form" @submit.prevent="save">
      <section class="admin-panel">
        <header class="admin-panel-head">
          <span class="admin-panel-icon"><FileText :size="17" /></span>
          <div class="admin-panel-titles"><h2>题目元数据</h2><p>标题、来源与难度等基础信息</p></div>
        </header>
        <div class="admin-panel-body">
          <div class="admin-form-grid admin-form-grid--three">
            <div class="form-field"><UiLabel>外部题目标识（可选）</UiLabel><UiInput v-model="form.externalKey" maxlength="128" placeholder="外部题号或来源标识" :disabled="creatingNextVersion" /></div>
            <div class="form-field"><UiLabel>学校</UiLabel><UiInput v-model="form.school" maxlength="200" /></div>
            <div class="form-field"><UiLabel>年份</UiLabel><UiNumberField v-model="form.year" :min="1900" :max="2200" /></div>
          </div>
          <div class="form-field"><UiLabel>标题</UiLabel><UiInput v-model="form.title" maxlength="200" /></div>
          <div class="admin-form-grid admin-form-grid--three">
            <div class="form-field"><UiLabel>难度</UiLabel><UiSelect v-model="form.difficulty" placeholder=""><option value="EASY">基础</option><option value="MEDIUM">综合</option><option value="HARD">高难</option></UiSelect></div>
            <div class="form-field"><UiLabel>标签（逗号分隔）</UiLabel><UiInput v-model="tagText" placeholder="动态规划, 图论" /></div>
            <div class="form-field"><UiLabel>来源链接</UiLabel><UiInput v-model="form.sourceUrl" placeholder="https://..." /></div>
          </div>
        </div>
      </section>

      <section class="admin-panel">
        <header class="admin-panel-head">
          <span class="admin-panel-icon"><BookOpen :size="17" /></span>
          <div class="admin-panel-titles"><h2>题面与限制</h2><p>结构化题面内容与基准资源限制</p></div>
        </header>
        <div class="admin-panel-body">
          <div class="form-field statement-form-item"><UiLabel>题面内容</UiLabel><ProblemStatementEditor v-model="form.statementMarkdown" /></div>
          <div class="admin-form-grid admin-form-grid--three">
            <div class="form-field"><UiLabel>基准时间限制（ms）</UiLabel><UiNumberField v-model="form.timeLimitMs" :min="100" :max="60000" :step="100" /></div>
            <div class="form-field"><UiLabel>基准内存限制（MiB）</UiLabel><UiNumberField v-model="form.memoryLimitMiB" :min="16" :max="2048" :step="16" /></div>
            <div class="form-field"><UiLabel>数据声明</UiLabel><UiInput v-model="form.dataNotice" maxlength="200" placeholder="AI 数据请注明非官方" /></div>
          </div>
        </div>
      </section>

      <footer class="admin-form-bar">
        <p class="admin-panel-hint">创建草稿后将进入编辑页录入测试点</p>
        <div class="admin-form-bar-actions">
          <UiButton type="submit" :loading="saving"><Save :size="16" />创建草稿并继续</UiButton>
        </div>
      </footer>
    </form>
  </section>
</template>
