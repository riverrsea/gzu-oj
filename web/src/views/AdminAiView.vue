<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { Bot, RefreshCw } from "@lucide/vue";
import { toast } from "../lib/notify";
import { useRoute } from "vue-router";
import { api } from "../api/client";
import type { AiRun, AiStepResponse } from "../api/types";
import UiButton from "../components/ui/Button.vue";
import UiCheckbox from "../components/ui/Checkbox.vue";
import UiInput from "../components/ui/Input.vue";
import UiNumberField from "../components/ui/NumberField.vue";
import UiLabel from "../components/ui/Label.vue";
import UiTextarea from "../components/ui/Textarea.vue";

/** 当前路由，用于接收新建题目页面传入的版本和运行标识。 */
const route = useRoute();
/** 待处理的草稿版本标识。 */
const versionId = ref("");
/** 本次需要生成的测试点数量。 */
const testCaseCount = ref(10);
/** 差分门禁通过后是否自动发布。 */
const autoPublish = ref(false);
/** 自动发布时从生成结果开头选作公开样例的数量。 */
const sampleCount = ref(0);
/** AI 启动或刷新状态。 */
const loading = ref(false);
/** 人工恢复提交状态。 */
const resuming = ref(false);
/** 人工澄清内容的 JSON 字符串。 */
const correction = ref("{}");
/** 当前展示的 AI 运行。 */
const run = ref<AiRun>();

/** 由可恢复的失败阶段映射出恢复动作；不可恢复时为 null。 */
const resumeAction = computed<"reanalyze" | "rereview" | null>(() => {
  const target = run.value?.resumeTarget;
  if (target === "ANALYZING") return "reanalyze";
  if (target === "REVIEWING") return "rereview";
  return null;
});

/** 恢复动作的可读说明。 */
const resumeActionLabel = computed(() => (resumeAction.value === "reanalyze" ? "重新分析题意" : "重新对抗审查"));

/** 把单个步骤的结构化响应转成可读 JSON 文本。 */
function prettyResponse(step: AiStepResponse): string {
  if (step.rawResponse) {
    try {
      return JSON.stringify(JSON.parse(step.rawResponse), null, 2);
    } catch {
      return step.rawResponse;
    }
  }
  if (step.response) return JSON.stringify(step.response, null, 2);
  return "（无结构化返回）";
}

/** 预填修改内容时最相关的失败步骤（优先匹配失败阶段，否则取最新一步）。 */
function failedStep(): AiStepResponse | undefined {
  const target = run.value?.resumeTarget;
  const steps = run.value?.steps ?? [];
  if (target && steps.length) {
    const match = [...steps].reverse().find((step) => step.state === target);
    if (match) return match;
  }
  return steps.length ? steps[steps.length - 1] : undefined;
}

/** 用失败步骤的模型返回预填修改区，便于直接编辑结构化内容。 */
function prefillCorrection(): void {
  const step = failedStep();
  if (!step) {
    correction.value = "{}";
    return;
  }
  if (step.rawResponse) {
    try {
      correction.value = JSON.stringify(JSON.parse(step.rawResponse), null, 2);
      return;
    } catch {
      correction.value = step.rawResponse;
      return;
    }
  }
  if (step.response) {
    correction.value = JSON.stringify(step.response, null, 2);
    return;
  }
  correction.value = "{}";
}

// 进入可恢复的人工接管状态时，用失败步骤的模型返回预填修改区。
watch(resumeAction, (action) => {
  if (action) prefillCorrection();
});

/** 启动外部 Python Agent 多角色录题流程。 */
async function start(): Promise<void> {
  if (!versionId.value.trim()) {
    toast.warning("请输入草稿版本 ID");
    return;
  }
  if (sampleCount.value < 0 || sampleCount.value > testCaseCount.value) {
    toast.warning("公开样例数量必须位于 0 到生成测试点数量之间");
    return;
  }
  if (!autoPublish.value && sampleCount.value > 0) {
    toast.warning("关闭自动发布时请将公开样例数量设为 0，生成后可在编辑页手动勾选");
    return;
  }
  loading.value = true;
  try {
    run.value = await api.startAiRun(versionId.value.trim(), testCaseCount.value, autoPublish.value, sampleCount.value);
    autoPublish.value = run.value.autoPublish;
    sampleCount.value = run.value.requestedSampleCount;
    toast.success("AI 流程已启动");
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "AI 流程启动失败");
  } finally {
    loading.value = false;
  }
}

/** 从后端刷新当前 AI 运行状态。 */
async function refresh(): Promise<void> {
  if (!run.value) return;
  loading.value = true;
  try {
    run.value = await api.aiRun(run.value.id);
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "AI 运行状态加载失败");
  } finally {
    loading.value = false;
  }
}

/** 人工接管：把澄清内容回传给 Agent 并恢复执行对应失败节点。 */
async function resume(): Promise<void> {
  if (!run.value || !resumeAction.value) return;
  let parsed: unknown = {};
  const text = correction.value.trim();
  if (text) {
    try {
      parsed = JSON.parse(text);
    } catch {
      toast.error("澄清内容不是合法 JSON，请检查格式");
      return;
    }
  }
  resuming.value = true;
  try {
    run.value = await api.resumeAiRun(run.value.id, { action: resumeAction.value, correction: parsed });
    toast.success("已恢复，Agent 将重新执行对应节点");
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "人工恢复失败");
  } finally {
    resuming.value = false;
  }
}

onMounted(async () => {
  if (typeof route.query.versionId === "string") versionId.value = route.query.versionId;
  if (typeof route.query.runId === "string") {
    loading.value = true;
    try {
      run.value = await api.aiRun(route.query.runId);
      versionId.value = run.value.problemVersionId;
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "AI 运行状态加载失败");
    } finally {
      loading.value = false;
    }
  }
});
</script>

<template>
  <section class="content-page content-page--modern oj-page admin-page">
    <div class="page-heading"><h1>AI 录题</h1></div>
    <section class="admin-tool-surface admin-ai-tool">
      <header><Bot :size="21" /><div><h2>启动录题流程</h2></div></header>
      <form class="problem-form" @submit.prevent="start">
        <div class="form-field"><UiLabel>草稿版本 ID</UiLabel><UiInput v-model="versionId" placeholder="题目草稿版本 UUID" /></div>
        <div class="form-field"><UiLabel>生成测试点数量</UiLabel><UiNumberField v-model="testCaseCount" :min="1" :max="200" /></div>
        <label class="checkbox-field"><UiCheckbox v-model="autoPublish" />差分通过后自动发布</label>
        <div v-if="autoPublish" class="form-field"><UiLabel>公开样例数量</UiLabel><UiNumberField v-model="sampleCount" :min="0" :max="testCaseCount" /></div>
        <div class="form-actions"><UiButton type="submit" :loading="loading">启动流程</UiButton></div>
      </form>
    </section>
    <section v-if="run" class="ai-run-summary">
      <header><div><strong>{{ run.state }}</strong><span>{{ run.id }}</span></div><button class="icon-button" type="button" title="刷新运行状态" :disabled="loading" @click="refresh"><RefreshCw :size="17" /></button></header>
      <dl><div><dt>模型</dt><dd>{{ run.model }}</dd></div><div><dt>计划测试点</dt><dd>{{ run.requestedTestCaseCount }}</dd></div><div><dt>已生成测试点</dt><dd>{{ run.generatedTestCases.length }}</dd></div><div><dt>发布方式</dt><dd>{{ run.autoPublish ? `自动发布 · 样例 ${run.requestedSampleCount} 个` : "人工检查后发布" }}</dd></div><div><dt>修复轮次</dt><dd>{{ run.repairRound }}</dd></div><div><dt>已完成角色</dt><dd>{{ run.completedRoles.length }}</dd></div></dl>
      <p v-if="run.failureReason">{{ run.failureReason }}</p>
    </section>
    <section v-if="run && run.state === 'NEEDS_REVIEW'" class="ai-resume-surface">
      <header><strong>人工接管恢复</strong></header>
      <p v-if="resumeAction" class="ai-resume-hint">
        当前失败阶段：<code>{{ run.resumeTarget }}</code>（{{ resumeActionLabel }}）。下方已预填失败步骤的模型返回，可直接修改其中内容（或补充澄清说明），修改后会作为澄清内容回传给 Agent 重新执行。
      </p>
      <p v-else class="ai-resume-hint ai-resume-hint--blocked">
        该运行处于人工接管，但当前失败阶段不支持自动恢复（通常为沙箱校验或基础设施连续失败），需人工处理后重新启动。
      </p>
      <template v-if="resumeAction">
        <div class="form-field"><UiLabel>澄清内容（JSON）</UiLabel><UiTextarea v-model="correction" :rows="6" placeholder='{"note":"已澄清题意歧义"}' /></div>
        <div class="form-actions"><UiButton type="button" variant="default" :loading="resuming" @click="resume">恢复并重新执行</UiButton></div>
      </template>
    </section>
    <section v-if="run && run.steps.length" class="ai-step-list">
      <header><strong>Agent 结构化步骤</strong></header>
      <div v-for="step in run.steps" :key="step.id" class="ai-step-item">
        <div class="ai-step-head">
          <span class="ai-step-role">{{ step.role }}</span>
          <span class="ai-step-state">{{ step.state }}</span>
          <span v-if="step.failureReason" class="ai-step-fail">{{ step.failureReason }}</span>
        </div>
        <pre>{{ prettyResponse(step) }}</pre>
      </div>
    </section>
  </section>
</template>

<style scoped>
.ai-resume-surface,
.ai-step-list {
  margin-top: 1.25rem;
  padding: 1rem 1.25rem;
  background: var(--surface-1, #fff);
  border: 1px solid var(--border-1, #e5e7eb);
  border-radius: 0.75rem;
}
.ai-resume-surface header,
.ai-step-list header {
  font-weight: 600;
  margin-bottom: 0.75rem;
}
.ai-resume-hint {
  margin-bottom: 0.75rem;
  color: var(--text-2, #6b7280);
}
.ai-resume-hint code {
  background: var(--surface-2, #f3f4f6);
  padding: 0.1rem 0.35rem;
  border-radius: 0.25rem;
}
.ai-resume-hint--blocked {
  color: var(--danger, #dc2626);
}
.ai-step-item {
  border-top: 1px solid var(--border-1, #e5e7eb);
  padding: 0.75rem 0;
}
.ai-step-head {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  align-items: center;
}
.ai-step-role {
  font-weight: 600;
}
.ai-step-state {
  font-size: 0.8rem;
  color: var(--text-2, #6b7280);
  background: var(--surface-2, #f3f4f6);
  border-radius: 0.25rem;
  padding: 0.1rem 0.4rem;
}
.ai-step-fail {
  font-size: 0.8rem;
  color: var(--danger, #dc2626);
}
.ai-step-item pre {
  margin: 0.5rem 0 0;
  padding: 0.75rem;
  overflow: auto;
  max-height: 18rem;
  background: var(--surface-2, #f3f4f6);
  border-radius: 0.5rem;
  font-size: 0.8rem;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
