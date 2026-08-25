<script setup lang="ts">
import { onMounted, ref } from "vue";
import { Bot, RefreshCw } from "@lucide/vue";
import { toast } from "../lib/notify";
import { useRoute } from "vue-router";
import { api } from "../api/client";
import type { AiRun } from "../api/types";
import UiButton from "../components/ui/Button.vue";
import UiCheckbox from "../components/ui/Checkbox.vue";
import UiInput from "../components/ui/Input.vue";
import UiNumberField from "../components/ui/NumberField.vue";
import UiLabel from "../components/ui/Label.vue";

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
/** 当前展示的 AI 运行。 */
const run = ref<AiRun>();

/** 启动 Spring AI 多角色录题流程。 */
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
  </section>
</template>
