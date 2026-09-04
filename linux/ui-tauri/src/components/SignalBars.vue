<script setup lang="ts">
import { computed } from "vue";

const props = defineProps<{ rssi: number }>();

const bars = computed(() => {
  const r = props.rssi;
  if (r >= -55) return 4;
  if (r >= -65) return 3;
  if (r >= -75) return 2;
  return 1;
});
</script>

<template>
  <div class="flex items-end gap-0.5 h-4">
    <div
      v-for="i in 4"
      :key="i"
      class="w-1 rounded-sm transition-colors"
      :class="i <= bars ? 'bg-primary' : 'bg-muted'"
      :style="{ height: `${(i / 4) * 100}%` }"
    />
  </div>
</template>
