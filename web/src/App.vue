<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { BookOpen, ClipboardList, Heart, LogIn, LogOut, Moon, Settings, Sun, Trophy } from "@lucide/vue";
import { api } from "./api/client";
import { loadSession, session, setSession } from "./stores/session";

type Theme = "system" | "light" | "dark";

const route = useRoute();
const router = useRouter();
const theme = ref<Theme>((localStorage.getItem("gzu-oj.theme") as Theme | null) ?? "system");
/** 移动端主导航是否展开。 */
const mobileNavOpen = ref(false);
/** 页面是否已经滚动，用于将透明顶栏切换为实体状态。 */
const topbarScrolled = ref(false);
const isWorkspace = computed(() => route.meta.workspace === true);

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

function cycleTheme(): void {
  applyTheme(theme.value === "system" ? "light" : theme.value === "light" ? "dark" : "system");
}

async function logout(): Promise<void> {
  await api.logout();
  setSession(null);
  await router.push("/problems");
}

onMounted(() => {
  updateTopbarState();
  window.addEventListener("scroll", updateTopbarState, { passive: true });
  applyTheme(theme.value);
  matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => {
    if (theme.value === "system") applyTheme("system");
  });
  void loadSession();
});

onBeforeUnmount(() => {
  window.removeEventListener("scroll", updateTopbarState);
});

watch(() => route.fullPath, () => {
  mobileNavOpen.value = false;
});
</script>

<template>
  <div class="app-shell app-shell--modern" :class="{ 'app-shell--workspace': isWorkspace }">
    <header class="topbar topbar--codex" :class="{ 'topbar--scrolled': topbarScrolled, 'topbar--menu-open': mobileNavOpen }">
      <div class="topbar-inner">
        <RouterLink class="brand topbar-brand" to="/problems" aria-label="研试 OJ 题库">
          <img src="/brand-mark.svg" alt="" />
          <span>研试 OJ</span>
        </RouterLink>
        <nav v-if="!isWorkspace" class="main-nav topbar-nav" aria-label="主导航">
          <RouterLink to="/problems"><BookOpen :size="16" />题库</RouterLink>
          <RouterLink v-if="session.user" to="/practice"><Heart :size="16" />练习簿</RouterLink>
          <RouterLink v-if="session.user" to="/submissions"><ClipboardList :size="16" />提交</RouterLink>
          <RouterLink v-if="session.user" to="/training"><Trophy :size="16" />训练</RouterLink>
          <RouterLink v-if="session.user?.role === 'ADMIN'" to="/admin"><Settings :size="16" />管理</RouterLink>
        </nav>
        <div class="topbar-actions">
          <RouterLink v-if="!session.user && !isWorkspace" class="topbar-cta" to="/login">开始做题</RouterLink>
          <button class="icon-button topbar-theme-button" type="button" :title="'主题：' + theme" @click="cycleTheme">
            <Sun v-if="theme === 'light'" :size="17" />
            <Moon v-else-if="theme === 'dark'" :size="17" />
            <span v-else class="system-theme">A</span>
          </button>
          <template v-if="session.user">
            <span class="user-name">{{ session.user.username }}</span>
            <button class="icon-button" type="button" title="退出登录" @click="logout"><LogOut :size="17" /></button>
          </template>
          <RouterLink v-else-if="!isWorkspace" class="command-link topbar-login" to="/login"><LogIn :size="16" />登录</RouterLink>
        </div>
        <button v-if="!isWorkspace" class="topbar-menu-toggle" type="button" :aria-expanded="mobileNavOpen" aria-label="打开导航菜单" @click="mobileNavOpen = !mobileNavOpen">
          <span :class="{ 'topbar-menu-toggle__line--open': mobileNavOpen }" />
          <span :class="{ 'topbar-menu-toggle__line--open': mobileNavOpen }" />
        </button>
      </div>
      <nav v-if="!isWorkspace && mobileNavOpen" class="topbar-mobile-nav" aria-label="移动端主导航">
        <RouterLink to="/problems"><BookOpen :size="16" />题库</RouterLink>
        <RouterLink v-if="session.user" to="/practice"><Heart :size="16" />练习簿</RouterLink>
        <RouterLink v-if="session.user" to="/submissions"><ClipboardList :size="16" />提交</RouterLink>
        <RouterLink v-if="session.user" to="/training"><Trophy :size="16" />训练</RouterLink>
        <RouterLink v-if="session.user?.role === 'ADMIN'" to="/admin"><Settings :size="16" />管理</RouterLink>
        <RouterLink v-if="!session.user" to="/login"><LogIn :size="16" />登录</RouterLink>
      </nav>
    </header>
    <main :class="isWorkspace ? 'workspace-main' : 'page-main'">
      <RouterView />
    </main>
  </div>
</template>
