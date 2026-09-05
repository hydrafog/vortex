<script setup lang="ts">
import { computed, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import {
  SolarDevices,
  SolarUsersGroup,
  SolarPhone,
  SolarChatSquare,
  SolarNotebook,
  SolarSettings,
  SolarChevronsLeft,
} from "@/lib/solarIcons";
import logo from "@/assets/vortex_logo.png";
import { unreadConversations } from "@/composables/useMessages";

const route = useRoute();
const router = useRouter();
const { t } = useI18n();

const collapsed = ref(true);

const items = computed(() => [
  { key: "home", icon: SolarDevices, to: "/", label: t("nav.home") },
  { key: "contacts", icon: SolarUsersGroup, to: "/contacts", label: t("nav.contacts") },
  { key: "recents", icon: SolarPhone, to: "/recents", label: t("nav.recents") },
  { key: "messages", icon: SolarChatSquare, to: "/messages", label: t("nav.messages") },
  { key: "notes", icon: SolarNotebook, to: "/notes", label: t("nav.notes") },
  { key: "settings", icon: SolarSettings, to: "/settings", label: t("nav.settings") },
]);

const isActive = (to: string) =>
  to === "/" ? route.path === "/" : route.path.startsWith(to);

const activeIndex = computed(() => items.value.findIndex((it) => isActive(it.to)));

const ITEM_STRIDE = 46;

function go(to: string) {
  if (route.path !== to) router.push(to);
}
</script>

<template>
  <aside
    class="flex flex-col shrink-0 overflow-hidden border-r border-border backdrop-blur-2xl px-4 pt-[22px] pb-3"
    :style="{
      width: collapsed ? '76px' : '236px',
      background: 'hsl(var(--card) / 0.72)',
      transition: 'width .42s cubic-bezier(.22,1,.36,1)',
    }"
  >
    <div
      data-tauri-drag-region
      class="flex items-center justify-start gap-[11px] px-2 pt-1 cursor-grab select-none"
    >
      <img
        :src="logo"
        alt="Vortex"
        class="h-[30px] w-[30px] shrink-0 rounded-md object-cover"
        style="filter: drop-shadow(0 0 5px hsl(var(--primary) / 0.45))"
      />
      <span
        class="text-[18px] font-semibold tracking-[-0.3px] whitespace-nowrap transition-[opacity,transform] duration-300"
        :class="collapsed ? 'opacity-0 translate-x-2' : 'opacity-100 translate-x-0'"
      >Vortex</span>
    </div>

    <nav class="relative mt-[30px] flex flex-col gap-1">
      <div
        v-show="activeIndex >= 0"
        class="absolute inset-x-0 top-0 h-[42px] rounded-xl z-0"
        :style="{
          background: 'hsl(var(--foreground) / 0.06)',
          transform: `translateY(${activeIndex * ITEM_STRIDE}px)`,
          transition: 'transform .44s cubic-bezier(.22,1,.36,1)',
        }"
      />

      <button
        v-for="it in items"
        :key="it.key"
        :title="it.label"
        class="relative z-[1] flex h-[42px] items-center justify-start gap-3 rounded-xl px-3 font-medium transition-colors"
        :class="isActive(it.to) ? 'text-foreground' : 'text-muted-foreground hover:text-secondary-foreground'"
        @click="go(it.to)"
      >
        <span class="relative inline-flex shrink-0">
          <component :is="it.icon" :size="19" :stroke-width="1.8" />
          <span
            v-if="it.key === 'messages' && unreadConversations > 0"
            class="absolute -top-1.5 -right-1.5 flex h-4 min-w-[16px] items-center justify-center rounded-full bg-primary px-0.5 text-[9px] font-semibold text-primary-foreground"
          >{{ unreadConversations > 99 ? "99+" : unreadConversations }}</span>
        </span>
        <span
          class="whitespace-nowrap text-sm transition-[opacity,transform] duration-300"
          :class="collapsed ? 'opacity-0 translate-x-2' : 'opacity-100 translate-x-0'"
        >{{ it.label }}</span>
      </button>
    </nav>

    <div class="flex-1" />

    <button
      :title="collapsed ? 'Expand' : 'Collapse'"
      class="flex h-10 items-center justify-start gap-3 rounded-xl px-3 text-muted-foreground transition-colors hover:text-secondary-foreground"
      @click="collapsed = !collapsed"
    >
      <SolarChevronsLeft
        :size="19"
        :stroke-width="1.9"
        class="shrink-0 transition-transform duration-[420ms]"
        :class="collapsed ? 'rotate-180' : ''"
        style="transition-timing-function: cubic-bezier(.22,1,.36,1)"
      />
      <span
        class="whitespace-nowrap text-sm transition-[opacity,transform] duration-300"
        :class="collapsed ? 'opacity-0 translate-x-2' : 'opacity-100 translate-x-0'"
      >Collapse</span>
    </button>
  </aside>
</template>
