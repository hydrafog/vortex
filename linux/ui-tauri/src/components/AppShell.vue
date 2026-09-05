<script setup lang="ts">
import { onMounted, onUnmounted, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import Sidebar from "@/components/Sidebar.vue";
import { primaryPeer } from "@/composables/useHome";

const route = useRoute();
const router = useRouter();

watch(primaryPeer, (peer) => {
  if (!peer && route.path !== "/") router.replace("/");
});

function onMouseNav(e: MouseEvent) {
  if (e.button === 3) {
    e.preventDefault();
    router.back();
  } else if (e.button === 4) {
    e.preventDefault();
    router.forward();
  }
}
function onKeyNav(e: KeyboardEvent) {
  if (e.key === "Escape" && /^\/messages\/./.test(route.path)) {
    router.back();
  } else if ((e.ctrlKey || e.metaKey) && e.key === "f") {
    const el = document.querySelector<HTMLInputElement>("input[data-search]");
    if (el) {
      e.preventDefault();
      el.focus();
      el.select();
    }
  }
}
onMounted(() => {
  window.addEventListener("mouseup", onMouseNav);
  window.addEventListener("keydown", onKeyNav);
});
onUnmounted(() => {
  window.removeEventListener("mouseup", onMouseNav);
  window.removeEventListener("keydown", onKeyNav);
});
</script>

<template>
  <div class="flex h-screen overflow-hidden bg-background text-foreground">
    <Sidebar v-if="primaryPeer" />

    <main
      class="flex-1 min-w-0 flex flex-col overflow-hidden relative h-full"
      style="background: radial-gradient(120% 80% at 100% -5%, hsl(var(--primary) / 0.05), transparent 50%)"
    >
      <div
        data-tauri-drag-region
        class="h-3 w-full shrink-0 cursor-grab active:cursor-grabbing select-none z-10"
      />
      <div class="flex-1 min-h-0 overflow-hidden">
        <router-view />
      </div>
    </main>
  </div>
</template>
