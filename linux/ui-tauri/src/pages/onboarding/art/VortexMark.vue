<script setup lang="ts">
import logo from "@/assets/vortex_logo.png";

const props = withDefaults(defineProps<{ size?: number; pulse?: boolean }>(), {
  size: 30,
  pulse: false,
});
</script>

<template>
  <img
    :src="logo"
    alt="Vortex"
    :width="props.size"
    :height="props.size"
    :style="{
      display: 'block',
      borderRadius: `${Math.round(props.size * 0.22)}px`,
      filter: 'drop-shadow(0 0 6px hsl(var(--primary) / 0.5))',
      ...(props.pulse
        ? { animation: 'vxo-logoPulse 3.4s ease-in-out infinite' }
        : {}),
    }"
  />
</template>
