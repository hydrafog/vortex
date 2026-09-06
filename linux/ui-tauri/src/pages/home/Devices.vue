<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { useI18n } from "vue-i18n";
import { invoke } from "@tauri-apps/api/core";
import { getLocalDeviceName } from "@/lib/bridge";
import {
  SolarLaptop,
  SolarSmartphone,
  SolarHeadphones,
  SolarVideocamera,
  SolarMonitorShare,
  SolarLoader,
  SolarAdd,
  SolarBellBing,
  SolarSend,
} from "@/lib/solarIcons";
import {
  activeEarbuds,
  batteryClass,
  batteryIcon,
  earbudsMenuOpen,
  forgetTarget,
  isSwitching,
  mirrorStarting,
  mirrorActive,
  openEarbudsPicker,
  openPairPhoneModal,
  phoneOnline,
  phoneConnecting,
  primaryPeer,
  primaryPeerState,
  startMirror,
} from "@/composables/useHome";

const { t } = useI18n();

const connectedCount = computed(
  () => 1 + (phoneOnline.value ? 1 : 0) + (activeEarbuds.value?.connected ? 1 : 0),
);

const cameraOn = ref(false);
async function toggleCamera() {
  cameraOn.value = !cameraOn.value;
  try {
    await invoke("set_camera_request", { on: cameraOn.value });
  } catch {
    cameraOn.value = !cameraOn.value;
  }
}

const ringing = ref(false);
let ringTimer: ReturnType<typeof setTimeout> | undefined;
async function ringPhone() {
  try {
    await invoke("ring_phone");
    ringing.value = true;
    clearTimeout(ringTimer);
    ringTimer = setTimeout(() => (ringing.value = false), 2500);
  } catch {
  }
}

const earbudsStatus = computed(() => {
  if (!activeEarbuds.value) return t("earbuds.not_connected");
  return activeEarbuds.value.on === "local" ? t("earbuds.on_local") : t("earbuds.on_peer");
});

const sendingFiles = ref(false);
async function pickAndSendFiles() {
  if (sendingFiles.value) return;
  sendingFiles.value = true;
  try {
    await invoke("pick_and_send_files");
  } catch (e) {
    console.error("Failed to send files:", e);
  } finally {
    sendingFiles.value = false;
  }
}

const localHost = ref("");
onMounted(async () => {
  localHost.value = await getLocalDeviceName();
});
</script>

<template>
  <div class="h-full flex flex-col gap-[22px] px-8 py-7 overflow-y-auto scrollbar-thin">
    <header>
      <h1 class="text-2xl font-semibold tracking-[-0.5px]">{{ t("nav.home") }}</h1>
      <p class="mt-0.5 text-[13.5px] text-muted-foreground">
        {{ t("home.synced", { count: connectedCount }) }}
      </p>
    </header>

    <div class="grid grid-cols-2 gap-4">
      <div class="vx-card col-span-2 flex items-center gap-4">
        <span class="vx-icon"><SolarLaptop class="h-6 w-6" /></span>
        <div class="shrink-0">
          <div class="flex items-center gap-2.5">
            <span class="text-base font-semibold">{{ localHost || t("device.this") }}</span>
            <span
              v-if="localHost && localHost.toLowerCase() !== t('device.this').toLowerCase()"
              class="rounded-full bg-primary/[0.12] px-2 py-[3px] text-[10px] font-semibold uppercase tracking-[0.6px] text-primary"
            >{{ t("device.this") }}</span>
          </div>
          <div class="mt-1.5 flex items-center gap-2">
            <span class="vx-dot bg-primary" />
            <span class="text-[12.5px] text-muted-foreground">{{ t("device.linux") }}</span>
          </div>
        </div>
      </div>

      <button
        v-if="!primaryPeer"
        class="vx-card flex flex-col items-center justify-center gap-2 py-7 text-center hover:border-primary/40"
        @click="openPairPhoneModal"
      >
        <span class="vx-icon"><SolarAdd class="h-5 w-5" /></span>
        <span class="text-sm font-medium">{{ t("pair.add_phone") }}</span>
        <span class="text-xs text-muted-foreground">{{ t("pair.add_phone_hint") }}</span>
      </button>

      <div
        v-else
        class="vx-card flex flex-col gap-3.5"
        @contextmenu.prevent="forgetTarget = primaryPeer"
      >
        <div class="flex items-center gap-3">
          <span class="vx-icon"><SolarSmartphone class="h-[22px] w-[22px]" /></span>
          <div class="min-w-0 flex-1">
            <div class="text-[15px] font-semibold">
              {{ primaryPeerState?.name || primaryPeer.peer_name || t("device.android") }}
            </div>
            <div class="mt-px text-xs text-muted-foreground">{{ t("device.android") }}</div>
          </div>
          <button
            v-if="phoneOnline"
            class="vx-ring"
            :class="{ 'vx-ring--on': ringing }"
            :title="t('ring.tip')"
            @click="ringPhone"
          >
            <SolarBellBing class="h-[18px] w-[18px]" :stroke-width="1.9" />
          </button>
        </div>
        <div class="flex items-center gap-2">
          <span
            class="vx-dot"
            :class="phoneOnline ? 'bg-primary vx-pulse' : phoneConnecting ? 'bg-amber-400 vx-pulse' : 'bg-muted-foreground'"
          />
          <span class="text-[13px] text-[hsl(var(--card-foreground)/0.82)]">
            {{ phoneOnline ? t("peers.connected") : phoneConnecting ? t("peers.connecting") : t("peers.offline") }}
          </span>
        </div>
        <div class="h-px bg-white/[0.06]" />
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-1.5">
            <component
              :is="batteryIcon(primaryPeerState?.battery ?? null, primaryPeerState?.charging ?? false)"
              class="h-[18px] w-[18px]"
              :class="batteryClass(primaryPeerState?.battery ?? null, primaryPeerState?.charging ?? false)"
            />
            <span class="text-[13px] font-medium" :class="batteryClass(primaryPeerState?.battery ?? null, primaryPeerState?.charging ?? false)">
              {{ primaryPeerState?.battery != null ? primaryPeerState.battery + "%" : "—" }}
            </span>
          </div>
          <span v-if="primaryPeerState?.charging" class="text-xs text-muted-foreground">Charging</span>
        </div>
        <div v-if="phoneOnline" class="mt-1.5 flex flex-wrap items-center gap-3">
          <button
            class="vx-chip relative"
            :class="{ 'vx-chip--live': mirrorActive }"
            :disabled="mirrorStarting"
            @click="startMirror"
          >
            <SolarLoader v-if="mirrorStarting" class="h-3.5 w-3.5 animate-spin" />
            <SolarMonitorShare v-else class="h-3.5 w-3.5" />
            {{ t("mirror.share_screen") }}
            <span class="vx-tag absolute -top-1.5 right-2">{{ t("mirror.experimental") }}</span>
          </button>
          <button class="vx-chip relative" :class="{ 'vx-chip--live': cameraOn }" @click="toggleCamera">
            <SolarVideocamera class="h-3.5 w-3.5" />
            {{ t("mirror.use_as_webcam") }}
            <span class="vx-tag absolute -top-1.5 right-2">{{ t("mirror.experimental") }}</span>
          </button>
          <button
            class="vx-chip"
            :disabled="sendingFiles"
            @click="pickAndSendFiles"
          >
            <SolarLoader v-if="sendingFiles" class="h-3.5 w-3.5 animate-spin" />
            <SolarSend v-else class="h-3.5 w-3.5" />
            {{ sendingFiles ? t("share.sending") : t("share.send_files") }}
          </button>
        </div>
      </div>

      <button
        v-if="!activeEarbuds"
        class="vx-card flex flex-col items-center justify-center gap-2 py-7 text-center hover:border-primary/40"
        @click="openEarbudsPicker"
      >
        <span class="vx-icon"><SolarAdd class="h-5 w-5" /></span>
        <span class="text-sm font-medium">{{ t("earbuds.add") }}</span>
        <span class="text-xs text-muted-foreground">{{ t("earbuds.add_hint") }}</span>
      </button>

      <div
        v-else
        class="vx-card flex flex-col gap-3.5"
        :class="{ 'opacity-60': isSwitching }"
        @contextmenu.prevent="earbudsMenuOpen = true"
      >
        <div class="flex items-center gap-3">
          <span class="vx-icon"><SolarHeadphones class="h-[22px] w-[22px]" /></span>
          <div class="min-w-0">
            <div class="text-[15px] font-semibold truncate">{{ activeEarbuds.name }}</div>
            <div class="mt-px text-xs text-muted-foreground">{{ t("device.earbuds") }}</div>
          </div>
        </div>
        <div class="flex items-center gap-2">
          <span class="vx-dot" :class="activeEarbuds.connected ? 'bg-primary' : 'bg-muted-foreground'" />
          <span class="text-[13px] text-[hsl(var(--card-foreground)/0.82)]">{{ earbudsStatus }}</span>
        </div>
        <div class="flex items-center gap-1.5">
          <component
            :is="batteryIcon(activeEarbuds.battery ?? null, false)"
            class="h-[18px] w-[18px]"
            :class="batteryClass(activeEarbuds.battery ?? null, false)"
          />
          <span class="text-[13px] font-medium" :class="batteryClass(activeEarbuds.battery ?? null, false)">
            {{ activeEarbuds.battery != null ? activeEarbuds.battery + "%" : "—" }}
          </span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.vx-card {
  @apply rounded-[20px] border border-border p-[18px] text-left transition-colors;
  background: hsl(var(--card));
}
.vx-icon {
  @apply flex h-[42px] w-[42px] shrink-0 items-center justify-center rounded-xl border border-white/[0.06] bg-white/[0.05];
  color: #e8eaed;
}
.vx-dot {
  @apply h-2 w-2 shrink-0 rounded-full;
}
.vx-chip {
  @apply inline-flex items-center gap-1.5 rounded-full border border-white/[0.08] bg-white/[0.05] px-[13px] py-2 text-[12.5px] font-medium transition-colors hover:bg-white/[0.09] hover:text-foreground disabled:opacity-50;
  color: #d4d6db;
}
.vx-chip--live {
  @apply border-primary/40 bg-primary/[0.14] text-primary;
}
.vx-ring {
  @apply flex h-9 w-9 shrink-0 items-center justify-center rounded-full transition-colors;
  color: hsl(var(--muted-foreground));
  border: 1px solid hsl(var(--border));
  background: hsl(var(--foreground) / 0.04);
}
.vx-ring:hover {
  color: hsl(var(--foreground));
  background: hsl(var(--foreground) / 0.08);
}
.vx-ring--on {
  color: hsl(var(--primary));
  border-color: hsl(var(--primary) / 0.4);
  background: hsl(var(--primary) / 0.14);
  animation: vx-ring-pulse 0.5s ease-in-out infinite;
}
@keyframes vx-ring-pulse {
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.12); }
}
</style>
