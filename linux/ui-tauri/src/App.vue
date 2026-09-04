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
  <!-- The clipboard popup window renders bare; the main window shows the
       first-run intro until it's completed, then the app shell. -->
  <RouterView v-if="bare" />
  <OnboardingFlow v-else-if="!introDone" />
  <AppShell v-else />
</template>
