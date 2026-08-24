<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { BookOpen, ClipboardList, Heart, LogIn, LogOut, Moon, Settings, Sun, Trophy } from "@lucide/vue";
import { api } from "./api/client";
import { loadSession, session, setSession } from "./stores/session";

type Theme = "system" | "light" | "dark";

const route = useRoute();
const router = useRouter();
const theme = ref<Theme>((localStorage.getItem("gzu-oj.theme") as Theme | null) ?? "system");
const isWorkspace = computed(() => route.meta.workspace === true);

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
  applyTheme(theme.value);
  matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => {
    if (theme.value === "system") applyTheme("system");
  });
  void loadSession();
});
</script>

<template>
  <div class="app-shell app-shell--modern" :class="{ 'app-shell--workspace': isWorkspace }">
    <header class="topbar">
      <RouterLink class="brand" to="/problems" aria-label="研试 OJ 题库">
        <img src="/brand-mark.svg" alt="" />
        <span>研试 OJ</span>
      </RouterLink>
      <nav v-if="!isWorkspace" class="main-nav" aria-label="主导航">
        <RouterLink to="/problems"><BookOpen :size="17" />题库</RouterLink>
        <RouterLink v-if="session.user" to="/practice"><Heart :size="17" />练习簿</RouterLink>
        <RouterLink v-if="session.user" to="/submissions"><ClipboardList :size="17" />提交</RouterLink>
        <RouterLink v-if="session.user" to="/training"><Trophy :size="17" />训练</RouterLink>
        <RouterLink v-if="session.user?.role === 'ADMIN'" to="/admin"><Settings :size="17" />管理</RouterLink>
      </nav>
      <div class="topbar-actions">
        <button class="icon-button" type="button" :title="'主题：' + theme" @click="cycleTheme">
          <Sun v-if="theme === 'light'" :size="18" />
          <Moon v-else-if="theme === 'dark'" :size="18" />
          <span v-else class="system-theme">A</span>
        </button>
        <template v-if="session.user">
          <span class="user-name">{{ session.user.username }}</span>
          <button class="icon-button" type="button" title="退出登录" @click="logout"><LogOut :size="18" /></button>
        </template>
        <RouterLink v-else class="command-link" to="/login"><LogIn :size="17" />登录</RouterLink>
      </div>
    </header>
    <main :class="isWorkspace ? 'workspace-main' : 'page-main'">
      <RouterView />
    </main>
  </div>
</template>
