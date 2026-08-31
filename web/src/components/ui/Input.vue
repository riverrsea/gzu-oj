<script setup lang="ts">
import { computed } from "vue";
import { cn } from "../../lib/utils";

const props = withDefaults(defineProps<{
  modelValue?: string | number;
  type?: string;
  placeholder?: string;
  disabled?: boolean;
  readonly?: boolean;
  required?: boolean;
  maxlength?: number | string;
  autocomplete?: string;
  class?: string;
}>(), { type: "text", modelValue: "" });

const emit = defineEmits<{ "update:modelValue": [value: string] }>();
const classes = computed(() => cn(
  "ui-control",
  props.class,
));
</script>

<template>
  <input :value="modelValue" :type="type" :placeholder="placeholder" :disabled="disabled" :readonly="readonly" :required="required" :maxlength="maxlength" :autocomplete="autocomplete" :class="classes" @input="emit('update:modelValue', ($event.target as HTMLInputElement).value)" />
</template>
