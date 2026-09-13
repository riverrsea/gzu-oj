<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { Check, ChevronDown } from "@lucide/vue";

/** 下拉选项；value 为空串表示“清除选择”的占位项。 */
export interface SelectMenuOption {
  value: string;
  label: string;
}

const props = withDefaults(defineProps<{
  modelValue?: string;
  options: SelectMenuOption[];
  placeholder?: string;
  disabled?: boolean;
  id?: string;
  ariaLabel?: string;
  /** 紧凑变体：用于工作区工具栏等高度受限的场景。 */
  compact?: boolean;
}>(), { modelValue: "", placeholder: "请选择" });
const emit = defineEmits<{ "update:modelValue": [value: string] }>();

/** 组件根节点，用于判断点击是否落在组件外部。 */
const root = ref<HTMLElement>();
/** 浮层面板节点，用于打开时把选中项滚动到可视区域。 */
const panel = ref<HTMLElement>();
/** 面板是否展开。 */
const open = ref(false);
/** 键盘与悬停高亮项的下标，-1 表示暂无高亮。 */
const highlighted = ref(-1);

/** 完整选项列表：占位符非空时作为空值选项置顶；空占位符表示必选，不提供空值项。 */
const items = computed<SelectMenuOption[]>(() =>
  props.placeholder ? [{ value: "", label: props.placeholder }, ...props.options] : props.options,
);
/** 触发按钮上显示的文本。 */
const currentLabel = computed(() => items.value.find((item) => item.value === props.modelValue)?.label ?? props.placeholder);

/** 将当前高亮项滚动到面板可视区域内。 */
function scrollHighlightedIntoView(): void {
  panel.value?.querySelector(".ui-select-menu-option--highlighted")?.scrollIntoView({ block: "nearest" });
}

/** 展开面板并把高亮定位到当前选中项。 */
function openPanel(): void {
  if (props.disabled) return;
  open.value = true;
  const selectedIndex = items.value.findIndex((item) => item.value === props.modelValue);
  highlighted.value = selectedIndex >= 0 ? selectedIndex : 0;
  void nextTick(scrollHighlightedIntoView);
}

function closePanel(): void {
  open.value = false;
  highlighted.value = -1;
}

function togglePanel(): void {
  if (open.value) closePanel();
  else openPanel();
}

/** 选择某项后通知父组件并关闭面板。 */
function select(option: SelectMenuOption): void {
  emit("update:modelValue", option.value);
  closePanel();
}

/** 触发按钮上的键盘操作：方向键移动高亮，回车确认，Esc 关闭。 */
function onTriggerKeydown(event: KeyboardEvent): void {
  if (event.key === "Escape") {
    if (open.value) closePanel();
    return;
  }
  if (event.key === "ArrowDown" || event.key === "ArrowUp") {
    event.preventDefault();
    if (!open.value) {
      openPanel();
      return;
    }
    const delta = event.key === "ArrowDown" ? 1 : -1;
    highlighted.value = (highlighted.value + delta + items.value.length) % items.value.length;
    void nextTick(scrollHighlightedIntoView);
    return;
  }
  if (event.key === "Enter" || event.key === " ") {
    event.preventDefault();
    if (!open.value) openPanel();
    else if (highlighted.value >= 0) select(items.value[highlighted.value]);
  }
}

/** 点击组件外部时关闭面板。 */
function onDocumentPointerdown(event: PointerEvent): void {
  if (open.value && root.value && event.target instanceof Node && !root.value.contains(event.target)) closePanel();
}

onMounted(() => window.addEventListener("pointerdown", onDocumentPointerdown));
onBeforeUnmount(() => window.removeEventListener("pointerdown", onDocumentPointerdown));
</script>

<template>
  <div ref="root" class="ui-select-menu" :class="{ 'ui-select-menu--compact': compact }">
    <button
      :id="id"
      type="button"
      class="ui-control ui-select-menu-trigger"
      :class="{ 'ui-select-menu-trigger--open': open, 'ui-select-menu-trigger--placeholder': modelValue === '' }"
      :disabled="disabled"
      aria-haspopup="listbox"
      :aria-expanded="open"
      :aria-label="ariaLabel"
      @click="togglePanel"
      @keydown="onTriggerKeydown"
    >
      <span class="ui-select-menu-value">{{ currentLabel }}</span>
      <ChevronDown :size="14" aria-hidden="true" class="ui-select-menu-chevron" />
    </button>
    <ul v-if="open" ref="panel" class="ui-select-menu-panel" role="listbox">
      <li
        v-for="(item, index) in items"
        :key="item.value || '__placeholder'"
        class="ui-select-menu-option"
        :class="{ 'ui-select-menu-option--selected': item.value === modelValue, 'ui-select-menu-option--highlighted': index === highlighted }"
        role="option"
        :aria-selected="item.value === modelValue"
        @click="select(item)"
        @pointerenter="highlighted = index"
      >
        <span class="ui-select-menu-option-label">{{ item.label }}</span>
        <Check v-if="item.value === modelValue" :size="14" aria-hidden="true" />
      </li>
    </ul>
  </div>
</template>
