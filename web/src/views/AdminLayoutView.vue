<script setup lang="ts">
import type { Component } from "vue";
import { computed } from "vue";
import { useRoute } from "vue-router";
import { BookOpen, ChevronRight, FileArchive, House, Plus, ServerCog } from "@lucide/vue";

/** 当前路由，用于面包屑当前页与顶部 Tab 的高亮匹配。 */
const route = useRoute();

/** 顶部 Tab 导航项；matches 决定该项在哪些路径下保持高亮。 */
interface AdminNavItem {
  to: string;
  label: string;
  icon: Component;
  matches: (path: string) => boolean;
}

/** 管理中心的顶部 Tab 导航；草稿编辑页属于题库目录的下级页面，保持目录项高亮。 */
const navItems: AdminNavItem[] = [
  {
    to: "/admin/problems",
    label: "题库目录",
    icon: BookOpen,
    matches: (path) => path === "/admin/problems" || (path.startsWith("/admin/problems/") && path !== "/admin/problems/new"),
  },
  { to: "/admin/problems/new", label: "新建题目", icon: Plus, matches: (path) => path === "/admin/problems/new" },
  { to: "/admin/imports", label: "批量导入", icon: FileArchive, matches: (path) => path.startsWith("/admin/imports") },
  { to: "/admin/workers", label: "判题 Worker", icon: ServerCog, matches: (path) => path.startsWith("/admin/workers") },
];

/** 面包屑最后一级：当前子页面名称；编辑页没有对应 Tab，单独命名。 */
const currentCrumb = computed(() => {
  const path = route.path;
  if (path.startsWith("/admin/problems/") && path !== "/admin/problems/new") return "编辑草稿";
  return navItems.find((item) => item.matches(path))?.label ?? "";
});
</script>

<template>
  <div class="admin-shell">
    <div class="admin-container">
      <!-- 面包屑：主站 / 管理中心 / 当前页 -->
      <nav class="admin-breadcrumb" aria-label="面包屑">
        <RouterLink to="/problems">主站</RouterLink>
        <ChevronRight :size="13" aria-hidden="true" />
        <RouterLink to="/admin">管理中心</RouterLink>
        <template v-if="currentCrumb">
          <ChevronRight :size="13" aria-hidden="true" />
          <span aria-current="page">{{ currentCrumb }}</span>
        </template>
      </nav>

      <!-- 下划线 Tab 导航，右端固定返回主站入口 -->
      <nav class="admin-tabs" aria-label="管理导航">
        <RouterLink
          v-for="item in navItems"
          :key="item.to"
          :to="item.to"
          class="admin-tab"
          :class="{ 'admin-tab--active': item.matches(route.path) }"
        >
          <component :is="item.icon" :size="15" />{{ item.label }}
        </RouterLink>
        <RouterLink to="/problems" class="admin-tab admin-tab--back"><House :size="15" />返回主站</RouterLink>
      </nav>

      <div class="admin-content"><RouterView /></div>
    </div>
  </div>
</template>
