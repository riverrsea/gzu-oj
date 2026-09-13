<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ArrowLeft, BookOpen, Bot, FileText, ListChecks, Plus, Save, Send, Trash2 } from "@lucide/vue";
import { confirmAction, toast } from "../lib/notify";
import { api } from "../api/client";
import type { AdminProblemVersionDetail, AiRun, Difficulty } from "../api/types";
import ProblemStatementEditor from "../components/ProblemStatementEditor.vue";
import AiRunOverlay from "../components/AiRunOverlay.vue";
import UiAlert from "../components/ui/Alert.vue";
import UiButton from "../components/ui/Button.vue";
import UiCheckbox from "../components/ui/Checkbox.vue";
import UiEmptyState from "../components/ui/EmptyState.vue";
import UiInput from "../components/ui/Input.vue";
import UiNumberField from "../components/ui/NumberField.vue";
import UiSelect from "../components/ui/Select.vue";
import UiLabel from "../components/ui/Label.vue";
import UiTextarea from "../components/ui/Textarea.vue";

interface TestCaseForm {
  input: string;
  output: string;
  sample: boolean;
  /** 该测试点来自哪一次 AI 运行；手动录入或导入时为 null。保存时必须原样回传。 */
  generatedByAiRunId: string | null;
}

const route = useRoute();
const router = useRouter();
const detail = ref<AdminProblemVersionDetail>();
const loading = ref(true);
const saving = ref(false);
const aiLoading = ref(false);
const aiRun = ref<AiRun>();
/** AI 生成测试点遮罩是否打开。 */
const overlayOpen = ref(false);
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

/** AI 运行期间锁定草稿内容；人工接管状态允许继续编辑。 */
const aiLocked = computed(() => {
  const state = aiRun.value?.state;
  if (state === "PUBLISHED") return true;
  if (state === "VALIDATING" && aiRun.value && !aiRun.value.autoPublish) return false;
  if (!detail.value?.activeAiRun) return false;
  if (!state) return true;
  return !isTerminalOrReview(state);
});

/** 是否为需要自动收起遮罩的终态。 */
function isTerminalState(state: string | undefined): boolean {
  return Boolean(state && ["PUBLISHED", "FAILED", "CANCELED"].includes(state));
}

/** 是否处于人工接管或终态（草稿可以解锁编辑）。 */
function isTerminalOrReview(state: string): boolean {
  return state === "NEEDS_REVIEW" || isTerminalState(state);
}

function addCase(): void {
  form.testCases.push({ input: "", output: "", sample: false, generatedByAiRunId: null });
}

function removeCase(index: number): void {
  if (form.testCases.length === 1) {
    toast.warning("至少需要一个测试点");
    return;
  }
  form.testCases.splice(index, 1);
}

async function load(): Promise<void> {
  try {
    detail.value = await api.adminProblemVersion(String(route.params.versionId));
    if (detail.value.status !== "DRAFT") {
      toast.warning("只有草稿版本可以编辑");
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
    form.testCases = detail.value.testCases.map(({ input, output, sample, generatedByAiRunId }) => ({
      input,
      output,
      sample,
      generatedByAiRunId: generatedByAiRunId ?? null,
    }));
    await loadAiRun();
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "草稿加载失败");
    await router.replace("/admin/problems");
  } finally {
    loading.value = false;
  }
}

/** 按草稿版本恢复当前 AI 运行，避免刷新页面后丢失状态时间线。 */
async function loadAiRun(): Promise<void> {
  if (!detail.value) return;
  try {
    aiRun.value = await api.activeAiRun(detail.value.versionId);
    if (aiRun.value && !isTerminalState(aiRun.value.state)) overlayOpen.value = true;
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "AI 状态加载失败");
  }
}

/** 刷新当前 AI 运行；没有运行时重新查询草稿绑定的运行。 */
async function refreshAi(): Promise<void> {
  if (!detail.value || aiLoading.value) return;
  aiLoading.value = true;
  try {
    const next = aiRun.value
      ? await api.aiRun(aiRun.value.id)
      : await api.activeAiRun(detail.value.versionId);
    const previousGeneratedCount = aiRun.value?.generatedTestCases.length ?? 0;
    aiRun.value = next;
    if (!next || isTerminalState(next.state)) detail.value.activeAiRun = false;
    // AI 写入测试点会改变草稿内容哈希；人工接管前重新加载，避免后续保存覆盖新数据。
    if (next && next.state !== "PUBLISHED" && next.generatedTestCases.length > previousGeneratedCount) await load();
    if (next && isTerminalState(next.state)) overlayOpen.value = false;
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "AI 状态刷新失败");
  } finally {
    aiLoading.value = false;
  }
}

/** 启动 AI 流程。管理员应先保存当前草稿再启动。 */
async function startAi(config: { testCaseCount: number; autoPublish: boolean; sampleCount: number }): Promise<void> {
  if (!detail.value || aiLoading.value) return;
  if (!Number.isInteger(config.testCaseCount) || config.testCaseCount < 1 || config.testCaseCount > 200) {
    toast.warning("AI 生成测试点数量必须位于 1 到 200");
    return;
  }
  if (!Number.isInteger(config.sampleCount) || config.sampleCount < 0 || config.sampleCount > config.testCaseCount) {
    toast.warning("公开样例数量必须位于 0 到生成测试点数量之间");
    return;
  }
  if (!config.autoPublish && config.sampleCount > 0) {
    toast.warning("关闭自动发布时请将公开样例数量设为 0，生成后可在测试点列表中手动勾选");
    return;
  }
  if (form.testCases.length > 0) {
    try {
      await confirmAction(
        config.autoPublish
          ? `当前草稿已有 ${form.testCases.length} 个测试点。AI 差分全部通过后，将用新生成的 ${config.testCaseCount} 个测试点替换它们，并自动发布；前 ${config.sampleCount} 个测试点会作为公开样例。`
          : `当前草稿已有 ${form.testCases.length} 个测试点。AI 差分全部通过后，将用新生成的 ${config.testCaseCount} 个测试点替换它们，但不会自动发布；你可以检查并手动勾选公开样例。` + "\n\n确认启动 AI 吗？",
      );
    } catch {
      return;
    }
  }
  aiLoading.value = true;
  try {
    aiRun.value = await api.startAiRun(
      detail.value.versionId,
      config.testCaseCount,
      config.autoPublish,
      config.sampleCount,
    );
    detail.value.activeAiRun = true;
    overlayOpen.value = true;
    toast.success("AI 录题流程已启动");
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "AI 流程启动失败");
  } finally {
    aiLoading.value = false;
  }
}

/** 取消当前 AI 运行并恢复人工编辑。 */
async function cancelAi(): Promise<void> {
  if (!aiRun.value || !detail.value || aiLoading.value) return;
  try {
    await confirmAction("取消后可以继续手工编辑草稿，已保存的 AI 步骤仍会保留。\n\n确认取消 AI 流程吗？");
  } catch {
    return;
  }
  aiLoading.value = true;
  try {
    aiRun.value = await api.cancelAiRun(aiRun.value.id);
    detail.value.activeAiRun = false;
    toast.success("AI 流程已取消，可继续人工编辑");
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "AI 流程取消失败");
  } finally {
    aiLoading.value = false;
  }
}

/** 人工接管：把修改后的结构化内容回传给 Agent 并恢复执行对应失败节点。 */
async function resumeAi(payload: { action: "reanalyze"; correction: unknown }): Promise<void> {
  if (!aiRun.value || aiLoading.value) return;
  aiLoading.value = true;
  try {
    aiRun.value = await api.resumeAiRun(aiRun.value.id, payload);
    overlayOpen.value = true;
    toast.success("已把修改内容回传给 Agent，重新执行对应节点");
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "人工恢复失败");
  } finally {
    aiLoading.value = false;
  }
}

async function save(publish: boolean): Promise<void> {
  if (!detail.value || saving.value || aiLocked.value) return;
  if (publish && form.testCases.length === 0) {
    toast.error("发布时至少需要一个测试点");
    return;
  }
  if (publish) {
    try {
      await confirmAction("发布后该版本将不可再编辑，并会成为这道题的当前公开版本。\n\n确认发布吗？");
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
    toast.success(publish ? "题目版本已发布" : "草稿已保存");
    if (publish) {
      await router.replace("/admin/problems");
    } else {
      await load();
    }
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "草稿保存失败");
  } finally {
    saving.value = false;
  }
}

// 运行进入终态时自动收起遮罩。
watch(() => aiRun.value?.state, (state) => {
  if (isTerminalState(state)) overlayOpen.value = false;
});

onMounted(async () => {
  await load();
});
</script>

<template>
  <section class="admin-page admin-page--narrow loading-shell" :aria-busy="loading">
    <div v-if="loading" class="loading-overlay"><span class="loading-spinner" aria-label="加载中" /></div>
    <div class="admin-page-head">
      <div>
        <h1>编辑题目草稿</h1>
      </div>
      <div class="admin-page-actions">
        <UiButton @click="overlayOpen = true"><Bot :size="16" />AI 生成测试点</UiButton>
        <UiButton variant="ghost" @click="router.push('/admin/problems')"><ArrowLeft :size="16" />返回题库</UiButton>
      </div>
    </div>

    <UiAlert v-if="aiLocked" variant="warning" title="该草稿存在进行中的 AI 流程，内容暂时锁定；流程结束或取消后可继续编辑。" />
    <UiAlert v-else-if="detail && form.testCases.length === 0" variant="info" title="当前草稿还没有测试点；请添加测试点并保存后才能发布。" />
    <form class="admin-form" @submit.prevent="save(false)">
      <section class="admin-panel">
        <header class="admin-panel-head">
          <span class="admin-panel-icon"><FileText :size="17" /></span>
          <div class="admin-panel-titles"><h2>题目元数据</h2><p>标题、来源与难度等基础信息</p></div>
        </header>
        <div class="admin-panel-body">
          <div class="admin-form-grid">
            <div class="form-field"><UiLabel>学校</UiLabel><UiInput v-model="form.school" maxlength="200" :disabled="aiLocked" /></div>
            <div class="form-field"><UiLabel>年份</UiLabel><UiNumberField v-model="form.year" :min="1900" :max="2200" :disabled="aiLocked" /></div>
          </div>
          <div class="form-field"><UiLabel>标题</UiLabel><UiInput v-model="form.title" maxlength="200" :disabled="aiLocked" /></div>
          <div class="admin-form-grid admin-form-grid--three">
            <div class="form-field"><UiLabel>难度</UiLabel><UiSelect v-model="form.difficulty" placeholder="" :disabled="aiLocked"><option value="EASY">基础</option><option value="MEDIUM">综合</option><option value="HARD">高难</option></UiSelect></div>
            <div class="form-field"><UiLabel>标签（逗号分隔）</UiLabel><UiInput v-model="tagText" :disabled="aiLocked" /></div>
            <div class="form-field"><UiLabel>来源链接</UiLabel><UiInput v-model="form.sourceUrl" placeholder="https://..." :disabled="aiLocked" /></div>
          </div>
        </div>
      </section>

      <section class="admin-panel">
        <header class="admin-panel-head">
          <span class="admin-panel-icon"><BookOpen :size="17" /></span>
          <div class="admin-panel-titles"><h2>题面与限制</h2><p>结构化题面内容与基准资源限制</p></div>
        </header>
        <div class="admin-panel-body">
          <div class="form-field statement-form-item"><UiLabel>题面内容</UiLabel><ProblemStatementEditor v-model="form.statementMarkdown" :disabled="aiLocked" /></div>
          <div class="admin-form-grid admin-form-grid--three">
            <div class="form-field"><UiLabel>基准时间限制（ms）</UiLabel><UiNumberField v-model="form.timeLimitMs" :min="100" :max="60000" :step="100" :disabled="aiLocked" /></div>
            <div class="form-field"><UiLabel>基准内存限制（MiB）</UiLabel><UiNumberField v-model="form.memoryLimitMiB" :min="16" :max="2048" :step="16" :disabled="aiLocked" /></div>
            <div class="form-field"><UiLabel>数据声明</UiLabel><UiInput v-model="form.dataNotice" maxlength="200" :disabled="aiLocked" /></div>
          </div>
        </div>
      </section>

      <section class="admin-panel">
        <header class="admin-panel-head">
          <span class="admin-panel-icon"><ListChecks :size="17" /></span>
          <div class="admin-panel-titles"><h2>测试点</h2><p>输入输出成对出现，得分按通过的测试点数折算</p></div>
          <div class="admin-panel-head-actions">
            <UiButton variant="outline" size="sm" :disabled="aiLocked" @click="addCase"><Plus :size="15" />添加测试点</UiButton>
          </div>
        </header>
        <div class="admin-panel-body">
          <div class="admin-case-grid">
            <article v-for="(item, index) in form.testCases" :key="index" class="admin-case-card">
              <header class="admin-case-card-head">
                <span class="admin-case-ordinal"><i>{{ index + 1 }}</i>测试点 {{ index + 1 }}</span>
                <button class="icon-button" type="button" title="删除测试点" :disabled="aiLocked" @click="removeCase(index)"><Trash2 :size="16" /></button>
              </header>
              <div class="admin-case-io">
                <div class="form-field"><UiLabel>输入</UiLabel><UiTextarea v-model="item.input" :rows="5" :disabled="aiLocked" /></div>
                <div class="form-field"><UiLabel>标准输出</UiLabel><UiTextarea v-model="item.output" :rows="5" :disabled="aiLocked" /></div>
              </div>
              <footer class="admin-case-foot">
                <label class="checkbox-field"><UiCheckbox v-model="item.sample" :disabled="aiLocked" />公开样例</label>
              </footer>
            </article>
          </div>
          <UiEmptyState v-if="form.testCases.length === 0" description="还没有测试点，点击右上角“添加测试点”开始录入" />
        </div>
      </section>

      <footer class="admin-form-bar">
        <p class="admin-panel-hint">得分按通过的测试点数折算，测试点不带分值</p>
        <div class="admin-form-bar-actions">
          <UiButton variant="outline" :disabled="aiLocked" :loading="saving" @click="save(false)"><Save :size="16" />保存草稿</UiButton>
          <UiButton :disabled="aiLocked" :loading="saving" @click="save(true)"><Send :size="16" />保存并发布</UiButton>
        </div>
      </footer>
    </form>

    <AiRunOverlay
      :open="overlayOpen"
      :run="aiRun ?? null"
      :loading="aiLoading"
      @close="overlayOpen = false"
      @start="startAi"
      @cancel="cancelAi"
      @refresh="refreshAi"
      @resume="resumeAi"
    />
  </section>
</template>
