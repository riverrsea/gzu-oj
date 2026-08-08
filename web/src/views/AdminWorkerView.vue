<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import { Copy, KeyRound, ServerCog, ShieldCheck } from "@lucide/vue";
import { ElMessage } from "element-plus";
import { api } from "../api/client";
import type { CreatedWorker } from "../api/types";

/** 新节点表单。 */
const form = reactive({
  name: "wsl-judge-1",
  slots: 4,
});
/** 创建请求是否正在进行。 */
const creating = ref(false);
/** 最近创建的节点凭据。 */
const created = ref<CreatedWorker>();
/** Token 是否刚刚复制成功。 */
const copied = ref(false);

/** Worker 名称必须符合后端的节点命名规则。 */
const validName = computed(() => /^[A-Za-z0-9][A-Za-z0-9._-]{1,99}$/.test(form.name.trim()));
/** 槽位数量必须落在后端允许的范围内。 */
const validSlots = computed(() => Number.isInteger(form.slots) && form.slots >= 1 && form.slots <= 64);

/** 创建节点凭据，并保留页面上的一次性展示结果。 */
async function createWorker(): Promise<void> {
  if (!validName.value) {
    ElMessage.warning("名称需为 2-100 位字母、数字、点、下划线或短横线");
    return;
  }
  if (!validSlots.value) {
    ElMessage.warning("并发槽位需设置为 1-64");
    return;
  }

  creating.value = true;
  try {
    created.value = await api.createWorker({ name: form.name.trim(), slots: form.slots });
    copied.value = false;
    ElMessage.success("Worker 凭据已创建");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Worker 凭据创建失败");
  } finally {
    creating.value = false;
  }
}

/** 将一次性 Token 复制到剪贴板。 */
async function copyToken(): Promise<void> {
  if (!created.value) return;
  try {
    await navigator.clipboard.writeText(created.value.token);
    copied.value = true;
    ElMessage.success("Token 已复制");
    window.setTimeout(() => {
      copied.value = false;
    }, 1800);
  } catch {
    ElMessage.error("复制失败，请手动选择 Token");
  }
}
</script>

<template>
  <section class="content-page admin-page worker-page">
    <div class="page-heading">
      <div>
        <div class="worker-eyebrow"><ServerCog :size="16" />节点凭据</div>
        <h1>判题 Worker</h1>
        <p>创建供独立 WSL 判题节点使用的 Bearer Token。</p>
      </div>
    </div>

    <div class="worker-layout">
      <article class="tool-card">
        <header>
          <ServerCog :size="21" />
          <div><h2>创建 Worker 凭据</h2><p>节点名称用于日志和租约审计；槽位数应与该主机可承受的并发判题数一致。</p></div>
        </header>

        <el-form label-position="top" @submit.prevent="createWorker">
          <el-form-item label="节点名称">
            <el-input v-model="form.name" maxlength="100" show-word-limit autocomplete="off" placeholder="wsl-judge-1" />
          </el-form-item>
          <el-form-item label="并发槽位">
            <el-input-number v-model="form.slots" :min="1" :max="64" controls-position="right" />
          </el-form-item>
          <el-alert title="Token 只返回一次" type="warning" :closable="false" show-icon>
            创建完成后请立即复制并保存。服务端只保存哈希，刷新或离开页面后无法再次查看。
          </el-alert>
          <div class="form-actions">
            <span>创建后还需要在 Worker 环境配置 go-judge Token。</span>
            <el-button type="primary" :loading="creating" :disabled="!validName || !validSlots" @click="createWorker">
              <KeyRound :size="16" />创建凭据
            </el-button>
          </div>
        </el-form>
      </article>

      <aside class="worker-side">
        <article v-if="created" class="tool-card worker-token-card">
          <header>
            <KeyRound :size="21" />
            <div><h2>凭据已创建</h2><p>{{ created.name }} · {{ created.slots }} 个并发槽位</p></div>
          </header>
          <div class="worker-id"><span>节点 ID</span><code>{{ created.id }}</code></div>
          <label class="token-label" for="worker-token">Worker Token（仅展示一次）</label>
          <div class="token-row">
            <el-input id="worker-token" :model-value="created.token" readonly />
            <el-button type="primary" :title="copied ? '已复制' : '复制 Token'" @click="copyToken"><Copy :size="16" />{{ copied ? '已复制' : '复制' }}</el-button>
          </div>
          <el-alert class="token-alert" title="请将 Token 写入 GZU_OJ_WORKER_TOKEN" type="success" :closable="false" show-icon />
        </article>

        <article class="tool-card">
          <header><ShieldCheck :size="21" /><div><h2>启动前检查</h2><p>Worker 心跳会拒绝不满足沙箱能力要求的节点。</p></div></header>
          <ul class="worker-checklist">
            <li><span class="check-dot" />WSL 发行版已启用 cgroup v2 的 CPU、内存和 PID 控制器</li>
            <li><span class="check-dot" />go-judge 使用与 Worker 配置一致的鉴权 Token</li>
            <li><span class="check-dot" />控制端地址可从判题主机通过 HTTPS 访问</li>
            <li><span class="check-dot" />Worker 槽位数不超过主机实际资源</li>
          </ul>
        </article>
      </aside>
    </div>
  </section>
</template>
