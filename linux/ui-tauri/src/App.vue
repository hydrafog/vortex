<script setup lang="ts">
import { computed, onMounted, onUnmounted } from "vue";
import { useRoute } from "vue-router";
import AppShell from "@/components/AppShell.vue";
import OnboardingFlow from "@/pages/onboarding/OnboardingFlow.vue";
import { introDone } from "@/lib/intro";

const route = useRoute();
const bare = computed(() => route.path.startsWith("/clipboard"));

function onContextMenu(e: MouseEvent) {
  const el = e.target as HTMLElement | null;
  if (el?.closest('input, textarea, [contenteditable=""], [contenteditable="true"], .selectable-text')) return;
  e.preventDefault();
}
onMounted(() => window.addEventListener("contextmenu", onContextMenu));
onUnmounted(() => window.removeEventListener("contextmenu", onContextMenu));
</script>

<template>
  <RouterView v-if="bare" />
  <OnboardingFlow v-else-if="!introDone" />
  <AppShell v-else />
</template>
