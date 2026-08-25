<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import { Copy, KeyRound, ServerCog, ShieldCheck } from "@lucide/vue";
import { toast } from "../lib/notify";
import { api } from "../api/client";
import type { CreatedWorker } from "../api/types";
import UiAlert from "../components/ui/Alert.vue";
import UiButton from "../components/ui/Button.vue";
import UiInput from "../components/ui/Input.vue";
import UiNumberField from "../components/ui/NumberField.vue";
import UiLabel from "../components/ui/Label.vue";

/** 新节点表单。 */
const form = reactive({
  name: "wsl-judge-1",
  slots: 4,
  aiSlots: 2,
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
/** AI 槽位允许关闭，最多同时运行十六份完整生成任务。 */
const validAiSlots = computed(() => Number.isInteger(form.aiSlots) && form.aiSlots >= 0 && form.aiSlots <= 16);

/** 创建节点凭据，并保留页面上的一次性展示结果。 */
async function createWorker(): Promise<void> {
  if (!validName.value) {
    toast.warning("名称需为 2-100 位字母、数字、点、下划线或短横线");
    return;
  }
  if (!validSlots.value) {
    toast.warning("并发槽位需设置为 1-64");
    return;
  }
  if (!validAiSlots.value) {
    toast.warning("AI 槽位需设置为 0-16");
    return;
  }

  creating.value = true;
  try {
    created.value = await api.createWorker({ name: form.name.trim(), slots: form.slots, aiSlots: form.aiSlots });
    copied.value = false;
    toast.success("Worker 凭据已创建");
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "Worker 凭据创建失败");
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
    toast.success("Token 已复制");
    window.setTimeout(() => {
      copied.value = false;
    }, 1800);
  } catch {
    toast.error("复制失败，请手动选择 Token");
  }
}
</script>

<template>
  <section class="content-page content-page--modern oj-page admin-page worker-page">
    <div class="page-heading">
      <div>
        <div class="worker-eyebrow"><ServerCog :size="16" />节点凭据</div>
        <h1>判题 Worker</h1>
      </div>
    </div>

    <div class="worker-layout">
      <article class="tool-card">
        <header>
          <ServerCog :size="21" />
          <div><h2>创建 Worker 凭据</h2></div>
        </header>

        <form class="problem-form" @submit.prevent="createWorker">
          <div class="form-field"><UiLabel>节点名称</UiLabel><UiInput v-model="form.name" maxlength="100" autocomplete="off" placeholder="wsl-judge-1" /></div>
          <div class="form-field"><UiLabel>并发槽位</UiLabel><UiNumberField v-model="form.slots" :min="1" :max="64" /></div>
          <div class="form-field"><UiLabel>AI 生成与差分槽位</UiLabel><UiNumberField v-model="form.aiSlots" :min="0" :max="16" /></div>
          <UiAlert title="Token 只返回一次" variant="warning">
            创建完成后请立即复制并保存。服务端只保存哈希，刷新或离开页面后无法再次查看。
          </UiAlert>
          <div class="form-actions">
            <UiButton :loading="creating" :disabled="!validName || !validSlots || !validAiSlots" @click="createWorker">
              <KeyRound :size="16" />创建凭据
            </UiButton>
          </div>
        </form>
      </article>

      <aside class="worker-side">
        <article v-if="created" class="tool-card worker-token-card">
          <header>
            <KeyRound :size="21" />
            <div><h2>凭据已创建</h2><p>{{ created.name }} · 普通 {{ created.slots }} 槽 · AI {{ created.aiSlots }} 槽</p></div>
          </header>
          <div class="worker-id"><span>节点 ID</span><code>{{ created.id }}</code></div>
          <label class="token-label" for="worker-token">Worker Token（仅展示一次）</label>
          <div class="token-row">
            <UiInput id="worker-token" :model-value="created.token" readonly />
            <UiButton :title="copied ? '已复制' : '复制 Token'" @click="copyToken"><Copy :size="16" />{{ copied ? '已复制' : '复制' }}</UiButton>
          </div>
          <UiAlert class="token-alert" title="请将 Token 写入 GZU_OJ_WORKER_TOKEN" variant="success" />
        </article>

        <article class="tool-card">
          <header><ShieldCheck :size="21" /><div><h2>启动前检查</h2></div></header>
          <ul class="worker-checklist">
            <li><span class="check-dot" />WSL 发行版已启用 cgroup v2 的 CPU、内存和 PID 控制器</li>
            <li><span class="check-dot" />go-judge 使用与 Worker 配置一致的鉴权 Token</li>
            <li><span class="check-dot" />控制端地址可从判题主机通过 HTTPS 访问</li>
            <li><span class="check-dot" />go-judge 并发数不小于普通槽与 AI 槽之和</li>
          </ul>
        </article>
      </aside>
    </div>
  </section>
</template>
