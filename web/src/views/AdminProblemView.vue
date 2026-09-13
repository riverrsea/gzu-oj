<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { Save, SlidersHorizontal, X } from "@lucide/vue";
import { toast } from "../lib/notify";
import { useRoute, useRouter } from "vue-router";
import { api } from "../api/client";
import type { AdminProblemVersionDetail, Difficulty } from "../api/types";
import MarkdownEditor from "../components/MarkdownEditor.vue";
import UiButton from "../components/ui/Button.vue";
import UiInput from "../components/ui/Input.vue";
import UiNumberField from "../components/ui/NumberField.vue";
import UiSelectMenu from "../components/ui/SelectMenu.vue";
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
/** 难度下拉选项；无占位空值项，难度必选。 */
const difficultyOptions = [
  { value: "EASY", label: "基础" },
  { value: "MEDIUM", label: "综合" },
  { value: "HARD", label: "高难" },
];

const form = reactive({
  externalKey: "",
  title: "",
  school: "贵州大学",
  year: new Date().getFullYear(),
  difficulty: "MEDIUM" as Difficulty,
  sourceUrl: "",
  // 预填章节骨架，与原结构化题面编辑器的章节保持一致
  statementMarkdown: "## 题目描述\n\n请填写题目背景、目标和要求。\n\n\n## 输入格式\n\n\n## 输出格式\n\n\n## 数据范围\n\n\n## 补充说明\n\n",
  timeLimitMs: 1000,
  memoryLimitMiB: 256,
  dataNotice: "",
});

/** 页面是否正在为已有逻辑题目创建新版本。 */
const creatingNextVersion = computed(() => Boolean(baseVersion.value));

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

/** 元数据抽屉是否展开；抽屉悬浮于页面之上，不挤压题面编辑区。 */
const metaOpen = ref(false);

/** Esc 键收起元数据抽屉。 */
function onGlobalKeydown(event: KeyboardEvent): void {
  if (event.key === "Escape" && metaOpen.value) metaOpen.value = false;
}

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

onMounted(() => {
  void loadBaseVersion();
  window.addEventListener("keydown", onGlobalKeydown);
});

onBeforeUnmount(() => window.removeEventListener("keydown", onGlobalKeydown));
</script>

<template>
  <section class="admin-page loading-shell" :aria-busy="loading">
    <div v-if="loading" class="loading-overlay"><span class="loading-spinner" aria-label="加载中" /></div>

    <!-- 题面编辑区为主内容，铺满页面宽度 -->
    <form class="admin-form" @submit.prevent="save">
      <MarkdownEditor v-model="form.statementMarkdown" />
      <div class="admin-form-grid admin-form-grid--three">
        <div class="form-field"><UiLabel>基准时间限制（ms）</UiLabel><UiNumberField v-model="form.timeLimitMs" :min="100" :max="60000" :step="100" /></div>
        <div class="form-field"><UiLabel>基准内存限制（MiB）</UiLabel><UiNumberField v-model="form.memoryLimitMiB" :min="16" :max="2048" :step="16" /></div>
        <div class="form-field"><UiLabel>数据声明</UiLabel><UiInput v-model="form.dataNotice" maxlength="200" placeholder="AI 数据请注明非官方" /></div>
      </div>

      <!-- 元数据抽屉：悬浮于页面右侧，展开时不挤压题面编辑区；点击遮罩空白处或按 Esc 收起 -->
      <Transition name="admin-meta-drawer-fade">
        <div v-if="metaOpen" class="admin-meta-drawer-layer" @click.self="metaOpen = false">
          <aside class="admin-meta-drawer" role="dialog" aria-modal="true" aria-label="题目信息">
            <div class="admin-meta-drawer-head">
              <strong>题目信息</strong>
              <button type="button" class="icon-button" aria-label="收起题目信息" @click="metaOpen = false"><X :size="16" /></button>
            </div>
            <div class="admin-meta-drawer-body">
              <div class="form-field"><UiLabel>标题</UiLabel><UiInput v-model="form.title" maxlength="200" /></div>
              <div class="form-field"><UiLabel>外部题目标识（可选）</UiLabel><UiInput v-model="form.externalKey" maxlength="128" placeholder="外部题号或来源标识" :disabled="creatingNextVersion" /></div>
              <div class="admin-form-grid">
                <div class="form-field"><UiLabel>学校</UiLabel><UiInput v-model="form.school" maxlength="200" /></div>
                <div class="form-field"><UiLabel>年份</UiLabel><UiSelectMenu v-model="yearSelect" :options="yearOptions" placeholder="" aria-label="年份" /></div>
              </div>
              <div class="form-field"><UiLabel>难度</UiLabel><UiSelectMenu v-model="form.difficulty" :options="difficultyOptions" placeholder="" aria-label="难度" /></div>
              <div class="form-field"><UiLabel>标签（逗号分隔）</UiLabel><UiInput v-model="tagText" placeholder="动态规划, 图论" /></div>
              <div class="form-field"><UiLabel>来源链接</UiLabel><UiInput v-model="form.sourceUrl" placeholder="https://..." /></div>
            </div>
          </aside>
        </div>
      </Transition>

      <footer class="admin-form-bar">
        <UiButton type="button" variant="outline" :aria-expanded="metaOpen" @click="metaOpen = !metaOpen"><SlidersHorizontal :size="15" />题目信息</UiButton>
        <div class="admin-form-bar-actions">
          <UiButton type="submit" :loading="saving"><Save :size="16" />创建草稿并继续</UiButton>
        </div>
      </footer>
    </form>
  </section>
</template>
