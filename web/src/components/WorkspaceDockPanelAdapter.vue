<script setup lang="ts">
import WorkspaceDockPanel from "./WorkspaceDockPanel.vue";
import type { WorkspacePanelContext, WorkspacePanelKind } from "../views/workspacePanel";

/** Dockview 注入给 Vue 面板组件的属性结构。 */
interface DockviewPanelProps {
  /** 业务面板参数位于 Dockview 参数对象的内层 params 字段。 */
  params: {
    /** 当前面板类型。 */
    kind: WorkspacePanelKind;
    /** 六个面板共享的响应式工作区上下文。 */
    context: WorkspacePanelContext;
  };
}

/** 将 Dockview 的嵌套参数转换为工作区面板的普通属性。 */
defineProps<{
  /** Dockview 传入的面板运行时参数。 */
  params: DockviewPanelProps;
}>();
</script>

<template>
  <WorkspaceDockPanel :kind="params.params.kind" :context="params.params.context" />
</template>
