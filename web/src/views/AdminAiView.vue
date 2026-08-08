<script setup lang="ts">
import { onMounted, ref } from "vue";
import { Bot, RefreshCw } from "@lucide/vue";
import { ElMessage } from "element-plus";
import { useRoute } from "vue-router";
import { api } from "../api/client";
import type { AiRun } from "../api/types";

/** 当前路由，用于接收新建题目页面传入的版本和运行标识。 */
const route = useRoute();
/** 待处理的草稿版本标识。 */
const versionId = ref("");
/** AI 启动或刷新状态。 */
const loading = ref(false);
/** 当前展示的 AI 运行。 */
const run = ref<AiRun>();

/** 启动 Spring AI 多角色录题流程。 */
async function start(): Promise<void> {
  if (!versionId.value.trim()) {
    ElMessage.warning("请输入草稿版本 ID");
    return;
  }
  loading.value = true;
  try {
    run.value = await api.startAiRun(versionId.value.trim());
    ElMessage.success("AI 流程已启动");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "AI 流程启动失败");
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
    ElMessage.error(error instanceof Error ? error.message : "AI 运行状态加载失败");
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
      ElMessage.error(error instanceof Error ? error.message : "AI 运行状态加载失败");
    } finally {
      loading.value = false;
    }
  }
});
</script>

<template>
  <section class="content-page admin-page">
    <div class="page-heading"><div><h1>AI 录题</h1><p>为现有草稿启动多角色生成、审查和差分验证流程</p></div></div>
    <section class="admin-tool-surface admin-ai-tool">
      <header><Bot :size="21" /><div><h2>启动录题流程</h2><p>标准输出必须由沙箱中的已校验标程计算，并通过确定性发布门禁。</p></div></header>
      <el-form label-position="top" @submit.prevent="start">
        <el-form-item label="草稿版本 ID"><el-input v-model="versionId" placeholder="题目草稿版本 UUID" /></el-form-item>
        <div class="form-actions"><span>Provider 默认关闭，可通过环境变量启用。</span><el-button type="primary" native-type="submit" :loading="loading">启动流程</el-button></div>
      </el-form>
    </section>
    <section v-if="run" class="ai-run-summary">
      <header><div><strong>{{ run.state }}</strong><span>{{ run.id }}</span></div><button class="icon-button" type="button" title="刷新运行状态" :disabled="loading" @click="refresh"><RefreshCw :size="17" /></button></header>
      <dl><div><dt>模型</dt><dd>{{ run.model }}</dd></div><div><dt>修复轮次</dt><dd>{{ run.repairRound }}</dd></div><div><dt>已完成角色</dt><dd>{{ run.completedRoles.length }}</dd></div></dl>
      <p v-if="run.failureReason">{{ run.failureReason }}</p>
    </section>
  </section>
</template>
