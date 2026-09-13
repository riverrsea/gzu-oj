<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { Bot, ListChecks, Plus, Save, Send, SlidersHorizontal, Trash2, X } from "@lucide/vue";
import { confirmAction, toast } from "../lib/notify";
import { api } from "../api/client";
import type { AdminProblemVersionDetail, AiRun, Difficulty } from "../api/types";
import MarkdownEditor from "../components/MarkdownEditor.vue";
import AiRunOverlay from "../components/AiRunOverlay.vue";
import UiAlert from "../components/ui/Alert.vue";
import UiButton from "../components/ui/Button.vue";
import UiCard from "../components/ui/Card.vue";
import UiCheckbox from "../components/ui/Checkbox.vue";
import UiEmptyState from "../components/ui/EmptyState.vue";
import UiInput from "../components/ui/Input.vue";
import UiNumberField from "../components/ui/NumberField.vue";
import UiSelectMenu from "../components/ui/SelectMenu.vue";
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

/** 可开关浮层的种类：题目信息抽屉或测试点遮罩。 */
type AdminPanel = "meta" | "cases";

/** 当前展开的浮层；悬浮于页面之上，不挤压题面编辑区，同一时间只展开一个。 */
const activePanel = ref<AdminPanel | null>(null);

/** 切换浮层开关；再次点击同一按钮时收起。 */
function togglePanel(panel: AdminPanel): void {
  activePanel.value = activePanel.value === panel ? null : panel;
}

/** Esc 键收起浮层。 */
function onGlobalKeydown(event: KeyboardEvent): void {
  if (event.key === "Escape" && activePanel.value) activePanel.value = null;
}

const tagText = ref("");
/** 难度下拉选项；无占位空值项，难度必选。 */
const difficultyOptions = [
  { value: "EASY", label: "基础" },
  { value: "MEDIUM", label: "综合" },
  { value: "HARD", label: "高难" },
];

/** 年份下拉选项：从当年回溯到 2000 年，倒序排列；与题库目录的年份筛选保持一致。 */
const yearOptions = computed(() => {
  const current = new Date().getFullYear();
  return Array.from({ length: current - 2000 + 1 }, (_, index) => {
    const year = current - index;
    return { value: String(year), label: String(year) };
  });
});

/** 年份下拉的字符串桥接：UiSelectMenu 使用字符串值，表单中保存为数字。 */
const yearSelect = computed<string>({
  get: () => String(form.year),
  set: (value) => {
    form.year = Number(value);
  },
});

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
  window.addEventListener("keydown", onGlobalKeydown);
});

onBeforeUnmount(() => window.removeEventListener("keydown", onGlobalKeydown));
</script>

<template>
  <section class="admin-page loading-shell" :aria-busy="loading">
    <div v-if="loading" class="loading-overlay"><span class="loading-spinner" aria-label="加载中" /></div>

    <UiAlert v-if="aiLocked" variant="warning" title="该草稿存在进行中的 AI 流程，内容暂时锁定；流程结束或取消后可继续编辑。" />
    <UiAlert v-else-if="detail && form.testCases.length === 0" variant="info" title="当前草稿还没有测试点；请添加测试点并保存后才能发布。" />
    <form class="admin-form" @submit.prevent="save(false)">
      <!-- 题面编辑区为主内容，铺满页面宽度 -->
      <MarkdownEditor v-model="form.statementMarkdown" :disabled="aiLocked" />
      <div class="admin-form-grid admin-form-grid--three">
        <div class="form-field"><UiLabel>基准时间限制（ms）</UiLabel><UiNumberField v-model="form.timeLimitMs" :min="100" :max="60000" :step="100" :disabled="aiLocked" /></div>
        <div class="form-field"><UiLabel>基准内存限制（MiB）</UiLabel><UiNumberField v-model="form.memoryLimitMiB" :min="16" :max="2048" :step="16" :disabled="aiLocked" /></div>
        <div class="form-field"><UiLabel>数据声明</UiLabel><UiInput v-model="form.dataNotice" maxlength="200" :disabled="aiLocked" /></div>
      </div>

      <!-- 元数据抽屉：悬浮于页面右侧，展开时不挤压题面编辑区；点击遮罩空白处或按 Esc 收起 -->
      <Transition name="admin-meta-drawer-fade">
        <div v-if="activePanel === 'meta'" class="admin-meta-drawer-layer" @click.self="activePanel = null">
          <aside class="admin-meta-drawer" role="dialog" aria-modal="true" aria-label="题目信息">
            <div class="admin-meta-drawer-head">
              <strong>题目信息</strong>
              <button type="button" class="icon-button" aria-label="收起题目信息" @click="activePanel = null"><X :size="16" /></button>
            </div>
            <div class="admin-meta-drawer-body">
              <div class="form-field"><UiLabel>标题</UiLabel><UiInput v-model="form.title" maxlength="200" :disabled="aiLocked" /></div>
              <div class="admin-form-grid">
                <div class="form-field"><UiLabel>学校</UiLabel><UiInput v-model="form.school" maxlength="200" :disabled="aiLocked" /></div>
                <div class="form-field"><UiLabel>年份</UiLabel><UiSelectMenu v-model="yearSelect" :options="yearOptions" placeholder="" :disabled="aiLocked" aria-label="年份" /></div>
              </div>
              <div class="form-field"><UiLabel>难度</UiLabel><UiSelectMenu v-model="form.difficulty" :options="difficultyOptions" placeholder="" :disabled="aiLocked" aria-label="难度" /></div>
              <div class="form-field"><UiLabel>标签（逗号分隔）</UiLabel><UiInput v-model="tagText" :disabled="aiLocked" /></div>
              <div class="form-field"><UiLabel>来源链接</UiLabel><UiInput v-model="form.sourceUrl" placeholder="https://..." :disabled="aiLocked" /></div>
            </div>
          </aside>
        </div>
      </Transition>

      <!-- 测试点遮罩：居中大面板，输入输出对照有充足空间；点击遮罩空白处或按 Esc 收起 -->
      <Transition name="admin-cases-fade">
        <div v-if="activePanel === 'cases'" class="admin-cases-overlay" @click.self="activePanel = null">
          <section class="admin-cases-panel" role="dialog" aria-modal="true" aria-label="测试点">
            <div class="admin-cases-panel-head">
              <strong>测试点（{{ form.testCases.length }}）</strong>
              <div class="admin-cases-panel-head-actions">
                <UiButton variant="outline" size="sm" :disabled="aiLocked" @click="addCase"><Plus :size="15" />添加测试点</UiButton>
                <button type="button" class="icon-button" aria-label="收起测试点" @click="activePanel = null"><X :size="16" /></button>
              </div>
            </div>
            <div class="admin-cases-panel-body">
              <div class="admin-case-grid">
                <UiCard v-for="(item, index) in form.testCases" :key="index" class="admin-case-card">
                  <header class="admin-case-card-head">
                    <span class="admin-case-ordinal"><i>{{ index + 1 }}</i>测试点 {{ index + 1 }}</span>
                    <UiButton variant="ghost" size="icon" title="删除测试点" :disabled="aiLocked" @click="removeCase(index)"><Trash2 :size="16" /></UiButton>
                  </header>
                  <div class="admin-case-io">
                    <div class="form-field"><UiLabel>输入</UiLabel><UiTextarea v-model="item.input" :rows="5" :disabled="aiLocked" /></div>
                    <div class="form-field"><UiLabel>标准输出</UiLabel><UiTextarea v-model="item.output" :rows="5" :disabled="aiLocked" /></div>
                  </div>
                  <footer class="admin-case-foot">
                    <label class="checkbox-field"><UiCheckbox v-model="item.sample" :disabled="aiLocked" />公开样例</label>
                  </footer>
                </UiCard>
              </div>
              <UiEmptyState v-if="form.testCases.length === 0" description="还没有测试点，点击右上角“添加测试点”开始录入" />
            </div>
          </section>
        </div>
      </Transition>

      <footer class="admin-form-bar">
        <div class="admin-form-bar-toggles">
          <UiButton type="button" variant="outline" :aria-expanded="activePanel === 'meta'" @click="togglePanel('meta')"><SlidersHorizontal :size="15" />题目信息</UiButton>
          <UiButton type="button" variant="outline" :aria-expanded="activePanel === 'cases'" @click="togglePanel('cases')"><ListChecks :size="15" />测试点（{{ form.testCases.length }}）</UiButton>
        </div>
        <div class="admin-form-bar-actions">
          <UiButton variant="outline" @click="overlayOpen = true"><Bot :size="16" />AI 生成测试点</UiButton>
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
