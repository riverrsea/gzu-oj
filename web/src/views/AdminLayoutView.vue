<script setup lang="ts">
import { BookOpen, FileArchive, Plus, ServerCog } from "@lucide/vue";
</script>

<template>
  <div class="admin-shell admin-shell--modern">
    <aside class="admin-sidebar admin-sidebar--modern">
      <header><strong>管理中心</strong></header>
      <nav aria-label="管理导航">
        <RouterLink to="/admin/problems"><BookOpen :size="17" />题库目录</RouterLink>
        <RouterLink to="/admin/problems/new"><Plus :size="17" />新建题目</RouterLink>
        <RouterLink to="/admin/imports"><FileArchive :size="17" />批量导入</RouterLink>
        <RouterLink to="/admin/workers"><ServerCog :size="17" />判题 Worker</RouterLink>
      </nav>
    </aside>
    <section class="admin-workspace admin-workspace--modern"><RouterView /></section>
  </div>
</template>
