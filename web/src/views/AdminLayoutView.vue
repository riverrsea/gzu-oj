<script setup lang="ts">
import type { Component } from "vue";
import { useRoute } from "vue-router";
import { BookOpen, FileArchive, House, Plus, ServerCog, SquareTerminal } from "@lucide/vue";

/** 当前路由，用于跨子路由保持侧栏导航高亮。 */
const route = useRoute();

/** 侧栏导航项；matches 决定该项在哪些路径下高亮。 */
interface AdminNavItem {
  to: string;
  label: string;
  icon: Component;
  matches: (path: string) => boolean;
}

/** 管理中心的分组导航：题库相关与基础设施分开，便于后续扩展。 */
const navGroups: { label: string; items: AdminNavItem[] }[] = [
  {
    label: "题库",
    items: [
      {
        to: "/admin/problems",
        label: "题库目录",
        icon: BookOpen,
        // 草稿编辑页属于题库目录的下级页面，保持目录项高亮。
        matches: (path) => path === "/admin/problems" || (path.startsWith("/admin/problems/") && path !== "/admin/problems/new"),
      },
      { to: "/admin/problems/new", label: "新建题目", icon: Plus, matches: (path) => path === "/admin/problems/new" },
      { to: "/admin/imports", label: "批量导入", icon: FileArchive, matches: (path) => path.startsWith("/admin/imports") },
    ],
  },
  {
    label: "基础设施",
    items: [{ to: "/admin/workers", label: "判题 Worker", icon: ServerCog, matches: (path) => path.startsWith("/admin/workers") }],
  },
];
</script>

<template>
  <div class="admin-shell">
    <aside class="admin-sidebar">
      <header class="admin-sidebar-brand">
        <span class="admin-sidebar-brand-icon"><SquareTerminal :size="18" /></span>
        <div class="admin-sidebar-brand-text">
          <strong>管理中心</strong>
          <span>GZU OJ 控制台</span>
        </div>
      </header>
      <nav class="admin-nav" aria-label="管理导航">
        <section v-for="group in navGroups" :key="group.label" class="admin-nav-group">
          <h2 class="admin-nav-label">{{ group.label }}</h2>
          <RouterLink
            v-for="item in group.items"
            :key="item.to"
            :to="item.to"
            class="admin-nav-link"
            :class="{ 'admin-nav-link--active': item.matches(route.path) }"
          >
            <component :is="item.icon" :size="16" />{{ item.label }}
          </RouterLink>
        </section>
      </nav>
      <footer class="admin-sidebar-footer">
        <RouterLink to="/problems" class="admin-nav-link"><House :size="16" />返回主站</RouterLink>
      </footer>
    </aside>
    <div class="admin-content"><RouterView /></div>
  </div>
</template>
