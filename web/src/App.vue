<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ArrowLeft, BookOpen, ChevronDown, ChevronLeft, ChevronRight, ClipboardList, Heart, List, LogIn, LogOut, Moon, Monitor, Pause, Play, Send, Settings, Square, Sun, Trophy, UserPlus } from "@lucide/vue";
import { api } from "./api/client";
import { loadSession, session, setSession } from "./stores/session";
import { workspaceToolbar } from "./stores/workspaceToolbar";
import ToastHost from "./components/ui/ToastHost.vue";
import UiButton from "./components/ui/Button.vue";

type Theme = "system" | "light" | "dark";

const route = useRoute();
const router = useRouter();
const theme = ref<Theme>((localStorage.getItem("gzu-oj.theme") as Theme | null) ?? "system");
/** 移动端主导航是否展开。 */
const mobileNavOpen = ref(false);
/** 页面是否已经滚动，用于将透明顶栏切换为实体状态。 */
const topbarScrolled = ref(false);
/** 登录用户菜单是否展开。 */
const userMenuOpen = ref(false);
const isWorkspace = computed(() => route.meta.workspace === true);
/** 当前是否处于独立的管理员壳层。 */
const isAdmin = computed(() => route.matched.some((record) => record.meta.admin === true));
/** 当前是否处于认证卡片页面。 */
const isAuth = computed(() => route.matched.some((record) => record.meta.auth === true));
/** 登录用户在顶栏头像中显示的首字母。 */
const userInitial = computed(() => session.user?.username.trim().slice(0, 1).toUpperCase() ?? "U");

/** 将个人计时剩余秒数转换为紧凑的顶栏倒计时。 */
function formatWorkspaceDuration(seconds: number): string {
  const safeSeconds = Math.max(0, Math.floor(seconds));
  const hours = Math.floor(safeSeconds / 3600);
  const minutes = Math.floor((safeSeconds % 3600) / 60);
  const secondPart = safeSeconds % 60;
  return hours > 0
    ? [hours, minutes, secondPart].map((part) => String(part).padStart(2, "0")).join(":")
    : [minutes, secondPart].map((part) => String(part).padStart(2, "0")).join(":");
}

/** 根据页面滚动位置更新顶栏状态。 */
function updateTopbarState(): void {
  topbarScrolled.value = window.scrollY > 8;
}

function applyTheme(value: Theme): void {
  theme.value = value;
  localStorage.setItem("gzu-oj.theme", value);
  const dark = value === "dark" || (value === "system" && matchMedia("(prefers-color-scheme: dark)").matches);
  document.documentElement.dataset.theme = dark ? "dark" : "light";
  window.dispatchEvent(new CustomEvent("gzu-oj-theme-change", { detail: { dark } }));
}

async function logout(): Promise<void> {
  await api.logout();
  setSession(null);
  userMenuOpen.value = false;
  await router.push("/problems");
}

/** 点击顶栏外部时收起登录用户菜单。 */
function closeUserMenu(event: PointerEvent): void {
  const target = event.target;
  if (target instanceof HTMLElement && !target.closest(".topbar-account")) userMenuOpen.value = false;
}

onMounted(() => {
  updateTopbarState();
  window.addEventListener("scroll", updateTopbarState, { passive: true });
  window.addEventListener("pointerdown", closeUserMenu);
  applyTheme(theme.value);
  matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => {
    if (theme.value === "system") applyTheme("system");
  });
  void loadSession();
});

onBeforeUnmount(() => {
  window.removeEventListener("scroll", updateTopbarState);
  window.removeEventListener("pointerdown", closeUserMenu);
});

watch(() => route.fullPath, () => {
  mobileNavOpen.value = false;
  userMenuOpen.value = false;
});
</script>

<template>
  <div class="app-shell app-shell--modern" :class="{ 'app-shell--workspace': isWorkspace, 'app-shell--admin': isAdmin }">
    <header class="topbar topbar--codex" :class="{ 'topbar--scrolled': topbarScrolled, 'topbar--menu-open': mobileNavOpen }">
      <div class="topbar-inner" :class="{ 'topbar-inner--workspace': isWorkspace }">
        <button v-if="isWorkspace && workspaceToolbar.active" class="workspace-back-button" type="button" title="返回题库" aria-label="返回题库" @click="workspaceToolbar.back?.()"><ArrowLeft :size="17" /></button>
        <button v-if="isWorkspace && workspaceToolbar.active" class="workspace-problem-list-button" :class="{ active: workspaceToolbar.problemListOpen }" type="button" title="题目列表" aria-label="题目列表" :aria-expanded="workspaceToolbar.problemListOpen" @click="workspaceToolbar.toggleProblemList?.()"><List :size="17" /></button>
        <RouterLink class="brand topbar-brand" to="/problems" aria-label="GZU_OJ 题库">
          <span>GZU_OJ</span>
        </RouterLink>
        <nav v-if="!isAdmin" class="main-nav topbar-nav" aria-label="主导航">
          <RouterLink to="/problems"><BookOpen :size="16" />题库</RouterLink>
          <RouterLink v-if="session.user" to="/practice"><Heart :size="16" />练习簿</RouterLink>
          <RouterLink v-if="session.user" to="/submissions"><ClipboardList :size="16" />提交</RouterLink>
          <RouterLink v-if="session.user" to="/training"><Trophy :size="16" />训练</RouterLink>
          <RouterLink v-if="session.user?.role === 'ADMIN'" to="/admin"><Settings :size="16" />管理</RouterLink>
        </nav>
        <div v-if="isWorkspace && workspaceToolbar.active" class="workspace-topbar-center" aria-label="做题操作">
          <button class="workspace-problem-nav-button" type="button" title="上一题" aria-label="上一题" :disabled="!workspaceToolbar.canPreviousProblem" @click="workspaceToolbar.previousProblem?.()"><ChevronLeft :size="17" /></button>
          <span v-if="workspaceToolbar.timedAttemptStatus" class="workspace-timed-countdown" :class="'workspace-timed-countdown--' + workspaceToolbar.timedAttemptStatus.toLowerCase()">{{ workspaceToolbar.timedAttemptStatus === "FINISHED" ? "已结束" : workspaceToolbar.timedAttemptStatus === "PAUSED" ? "已暂停 " + formatWorkspaceDuration(workspaceToolbar.timedAttemptRemainingSeconds) : formatWorkspaceDuration(workspaceToolbar.timedAttemptRemainingSeconds) }}</span>
          <span v-else-if="workspaceToolbar.contestRemainingSeconds !== undefined" class="workspace-timed-countdown">剩余 {{ formatWorkspaceDuration(workspaceToolbar.contestRemainingSeconds) }}</span>
          <UiButton v-if="workspaceToolbar.timedAttemptStatus === 'RUNNING'" variant="outline" size="sm" class="workspace-timed-action" :loading="workspaceToolbar.timedAttemptActionLoading" @click="workspaceToolbar.pauseTimedAttempt?.()"><Pause :size="14" aria-hidden="true" />暂停</UiButton>
          <UiButton v-else-if="workspaceToolbar.timedAttemptStatus === 'PAUSED'" variant="outline" size="sm" class="workspace-timed-action" :loading="workspaceToolbar.timedAttemptActionLoading" @click="workspaceToolbar.resumeTimedAttempt?.()"><Play :size="14" aria-hidden="true" />继续</UiButton>
          <UiButton variant="outline" size="sm" class="workspace-run-button" :loading="workspaceToolbar.running" :disabled="!workspaceToolbar.ready || workspaceToolbar.submitting || workspaceToolbar.coolingDown || workspaceToolbar.contestRemainingSeconds === 0 || Boolean(workspaceToolbar.timedAttemptStatus && workspaceToolbar.timedAttemptStatus !== 'RUNNING')" @click="workspaceToolbar.run?.()"><Play :size="14" fill="currentColor" aria-hidden="true" />运行</UiButton>
          <UiButton variant="default" size="sm" class="workspace-submit-button" :loading="workspaceToolbar.submitting" :disabled="!workspaceToolbar.ready || workspaceToolbar.running || workspaceToolbar.coolingDown || workspaceToolbar.contestRemainingSeconds === 0 || Boolean(workspaceToolbar.timedAttemptStatus && workspaceToolbar.timedAttemptStatus !== 'RUNNING')" @click="workspaceToolbar.submit?.()"><Send :size="14" aria-hidden="true" />提交</UiButton>
          <UiButton v-if="workspaceToolbar.timedAttemptStatus && workspaceToolbar.timedAttemptStatus !== 'FINISHED'" variant="destructive" size="sm" class="workspace-timed-finish" :disabled="workspaceToolbar.timedAttemptActionLoading" @click="workspaceToolbar.finishTimedAttempt?.()"><Square :size="13" aria-hidden="true" />提前结束</UiButton>
          <button class="workspace-problem-nav-button" type="button" title="下一题" aria-label="下一题" :disabled="!workspaceToolbar.canNextProblem" @click="workspaceToolbar.nextProblem?.()"><ChevronRight :size="17" /></button>
        </div>
        <div class="topbar-actions">
          <div class="topbar-theme-toggle" role="radiogroup" aria-label="主题">
            <button class="topbar-theme-option" :class="{ active: theme === 'light' }" type="button" role="radio" :aria-checked="theme === 'light'" title="浅色主题" @click="applyTheme('light')"><Sun :size="14" /></button>
            <button class="topbar-theme-option" :class="{ active: theme === 'system' }" type="button" role="radio" :aria-checked="theme === 'system'" title="跟随系统" @click="applyTheme('system')"><Monitor :size="14" /></button>
            <button class="topbar-theme-option" :class="{ active: theme === 'dark' }" type="button" role="radio" :aria-checked="theme === 'dark'" title="深色主题" @click="applyTheme('dark')"><Moon :size="14" /></button>
          </div>
          <template v-if="session.user">
            <div class="topbar-account">
              <button class="topbar-user-trigger" type="button" :aria-expanded="userMenuOpen" aria-haspopup="menu" @click.stop="userMenuOpen = !userMenuOpen">
                <span class="topbar-user-avatar" aria-hidden="true">{{ userInitial }}</span>
                <span class="topbar-user-label">{{ session.user.username }}</span>
                <ChevronDown :size="14" aria-hidden="true" />
              </button>
              <div v-if="userMenuOpen" class="topbar-user-menu" role="menu">
                <div class="topbar-user-menu-label">{{ session.user.username }}</div>
                <button class="topbar-user-menu-item" type="button" role="menuitem" @click="logout"><LogOut :size="15" />退出登录</button>
              </div>
            </div>
          </template>
          <template v-else-if="!isAuth">
            <RouterLink class="command-link topbar-login" to="/login">登录</RouterLink>
            <RouterLink class="topbar-register" to="/register">注册</RouterLink>
          </template>
        </div>
        <button v-if="!isAdmin" class="topbar-menu-toggle" type="button" :aria-expanded="mobileNavOpen" aria-label="打开导航菜单" @click="mobileNavOpen = !mobileNavOpen">
          <span :class="{ 'topbar-menu-toggle__line--open': mobileNavOpen }" />
          <span :class="{ 'topbar-menu-toggle__line--open': mobileNavOpen }" />
        </button>
      </div>
      <nav v-if="!isAdmin && mobileNavOpen" class="topbar-mobile-nav" aria-label="移动端主导航">
        <RouterLink to="/problems"><BookOpen :size="16" />题库</RouterLink>
        <RouterLink v-if="session.user" to="/practice"><Heart :size="16" />练习簿</RouterLink>
        <RouterLink v-if="session.user" to="/submissions"><ClipboardList :size="16" />提交</RouterLink>
        <RouterLink v-if="session.user" to="/training"><Trophy :size="16" />训练</RouterLink>
        <RouterLink v-if="session.user?.role === 'ADMIN'" to="/admin"><Settings :size="16" />管理</RouterLink>
        <RouterLink v-if="!session.user && !isAuth" to="/login"><LogIn :size="16" />登录</RouterLink>
        <RouterLink v-if="!session.user && !isAuth" to="/register"><UserPlus :size="16" />注册</RouterLink>
      </nav>
    </header>
    <main :class="isWorkspace ? 'workspace-main' : isAdmin ? 'admin-main' : isAuth ? 'auth-main' : 'page-main'">
      <RouterView />
    </main>
    <ToastHost />
  </div>
</template>
