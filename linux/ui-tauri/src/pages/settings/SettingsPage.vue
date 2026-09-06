<script setup lang="ts">
import { onMounted, onUnmounted, ref, computed } from "vue";
import { useI18n } from "vue-i18n";
import { useRouter } from "vue-router";
import {
  SolarMoon,
  SolarSun,
  SolarGlobe,
  SolarArrowLeft,
  SolarHeadphones,
  SolarBell,
  SolarBellBing,
  SolarLock,
  SolarUnlock,
  SolarClipboardList,
  SolarCursor,
  SolarFileDownload,
  SolarDevices,
} from "@/lib/solarIcons";
import {
  theme,
  themePreference,
  setThemePreference,
  accentPreference,
  setAccentPreference,
  detectedSystemAccent,
  ACCENT_PRESETS,
  type ThemePreference,
} from "@/lib/theme";
import { smartSwitchEnabled, setSmartSwitch } from "@/lib/smartSwitch";
import { notifMirrorShow, setNotifMirror, notifMirrorSend, setNotifSend } from "@/lib/notifMirror";
import {
  proximityAutoLock,
  proximityAutoUnlock,
  setProximityAutoLock,
  setProximityAutoUnlock,
  initProximity,
} from "@/lib/proximity";
import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import { setLocale, LOCALES, type LocaleCode } from "@/lib/i18n";
import { cn } from "@/lib/utils";
import SettingsRow from "@/components/SettingsRow.vue";

const router = useRouter();
const { t, te, locale } = useI18n();

onMounted(() => {
  void initProximity();
  void invoke<boolean>("get_clipboard_sync")
    .then((v) => (clipboardSync.value = v))
    .catch(() => {});
  void invoke<boolean>("get_file_auto_accept")
    .then((v) => (fileAutoAccept.value = v))
    .catch(() => {});
  void invoke<boolean>("uc_running")
    .then((v) => (ucEnabled.value = v))
    .catch(() => {});
  void invoke<string>("uc_get_placement")
    .then((v) => (ucPlacement.value = v))
    .catch(() => {});
  void listen<string>("vortex:uc-stopped", (e) => {
    ucEnabled.value = false;
    ucError.value = e.payload;
  }).then((un) => (unlistenUc = un));
});

let unlistenUc: UnlistenFn | undefined;
onUnmounted(() => unlistenUc?.());

const ucEnabled = ref(false);
const ucPlacement = ref("right");
const ucError = ref("");
const ucErrorText = computed(() => {
  if (!ucError.value) return "";
  const cut = ucError.value.indexOf("|");
  const code = cut < 0 ? ucError.value : ucError.value.slice(0, cut);
  const detail = cut < 0 ? "" : ucError.value.slice(cut + 1);
  const key = `settings.uc_err_${code}`;
  if (!te(key)) return ucError.value;
  return detail ? `${t(key)} (${detail})` : t(key);
});
const UC_EDGES = computed(() =>
  (["left", "right", "top", "bottom"] as const).map((code) => ({
    code,
    label: t(`settings.uc_edge_${code}`),
  })),
);
const ucEdge = computed(() => ucPlacement.value.split("-")[0] || "right");
const ucEnd = computed(() => ucPlacement.value.split("-")[1] ?? "");
const UC_ENDS = computed(() => {
  const ends =
    ucEdge.value === "top" || ucEdge.value === "bottom"
      ? (["left", "right"] as const)
      : (["top", "bottom"] as const);
  return [
    { code: "", label: t("settings.uc_end_whole") },
    ...ends.map((code) => ({ code, label: t(`settings.uc_corner_${code}`) })),
  ];
});
function setUniversalControl(v: boolean) {
  ucEnabled.value = v;
  ucError.value = "";
  void invoke(v ? "uc_start" : "uc_stop").catch((e) => {
    ucEnabled.value = !v;
    ucError.value = String(e);
  });
}
function pickUcPlacement(code: string) {
  ucPlacement.value = code;
  void invoke("uc_set_placement", { edge: code }).catch(() => {});
}
function pickUcEnd(code: string) {
  pickUcPlacement(code ? `${ucEdge.value}-${code}` : ucEdge.value);
}

const clipboardSync = ref(true);
function setClipboardSync(v: boolean) {
  clipboardSync.value = v;
  void invoke("set_clipboard_sync", { enabled: v }).catch(() => {});
}

const fileAutoAccept = ref(false);
function setFileAutoAccept(v: boolean) {
  const prev = fileAutoAccept.value;
  fileAutoAccept.value = v;
  void invoke("set_file_auto_accept", { enabled: v }).catch(() => {
    fileAutoAccept.value = prev;
  });
}

function pickLocale(code: LocaleCode) {
  setLocale(code);
}
function pickTheme(mode: ThemePreference) {
  setThemePreference(mode);
}

const pill = (active: boolean) =>
  cn(
    "flex-1 flex items-center justify-center gap-2 py-[11px] rounded-xl text-[13.5px] font-semibold cursor-pointer transition-all",
    active
      ? "bg-primary/15 border border-primary/50 text-primary"
      : "bg-muted/40 border border-border text-foreground hover:bg-muted/70",
  );
</script>

<template>
  <div class="h-full flex flex-col bg-background overflow-y-auto scrollbar-thin">
    <header class="flex items-center gap-2 px-5 py-4 border-b border-border bg-card/30 shrink-0">
      <button
        class="h-8 w-8 rounded-md flex items-center justify-center hover:bg-accent transition-colors"
        @click="router.push('/')"
      >
        <SolarArrowLeft class="h-4 w-4" />
      </button>
      <h1 class="text-base font-semibold">{{ t("settings.title") }}</h1>
    </header>

    <main class="flex-1 overflow-auto">
      <div class="w-full px-7 pt-6 pb-14">
        <div class="sec-label mt-0">{{ t("settings.sec_appearance") }}</div>
        <div class="rounded-[20px] border border-border bg-card overflow-hidden">
          <div class="px-[18px] py-4">
            <div class="flex items-center gap-2.5 mb-3">
              <SolarGlobe class="h-[18px] w-[18px] text-muted-foreground" :stroke-width="1.8" />
              <span class="text-sm font-semibold text-foreground">{{ t("settings.language") }}</span>
            </div>
            <div class="flex gap-2.5">
              <button
                v-for="l in LOCALES"
                :key="l.code"
                :class="pill(locale === l.code)"
                @click="pickLocale(l.code)"
              >
                {{ l.label }}
              </button>
            </div>
          </div>
          <div class="h-px bg-border/60" />
          <div class="px-[18px] py-4">
            <div class="flex items-center gap-2.5 mb-3">
              <component :is="theme === 'dark' ? SolarMoon : SolarSun" class="h-[18px] w-[18px] text-muted-foreground" :stroke-width="1.9" />
              <span class="text-sm font-semibold text-foreground">{{ t("settings.theme") }}</span>
            </div>
            <div class="flex gap-2.5">
              <button :class="pill(themePreference === 'system')" @click="pickTheme('system')">
                <SolarDevices class="h-4 w-4" :stroke-width="1.9" />{{ t("settings.theme_system") }}
              </button>
              <button :class="pill(themePreference === 'dark')" @click="pickTheme('dark')">
                <SolarMoon class="h-4 w-4" :stroke-width="1.9" />{{ t("settings.theme_dark") }}
              </button>
              <button :class="pill(themePreference === 'light')" @click="pickTheme('light')">
                <SolarSun class="h-4 w-4" :stroke-width="1.9" />{{ t("settings.theme_light") }}
              </button>
            </div>
          </div>
          <div class="h-px bg-border/60" />
          <div class="px-[18px] py-4">
            <div class="flex items-center gap-2.5 mb-3">
              <SolarDevices class="h-[18px] w-[18px] text-muted-foreground" :stroke-width="1.9" />
              <span class="text-sm font-semibold text-foreground">{{ t("settings.accent_color") }}</span>
            </div>
            <div class="flex flex-wrap gap-2">
              <button
                v-for="preset in ACCENT_PRESETS"
                :key="preset.code"
                class="flex items-center gap-2 px-3 py-2 rounded-xl text-xs font-semibold border transition-all cursor-pointer"
                :class="
                  accentPreference === preset.code
                    ? 'border-primary bg-primary/10 text-primary'
                    : 'border-border bg-muted/40 text-foreground hover:bg-muted/70'
                "
                @click="setAccentPreference(preset.code)"
              >
                <span
                  class="w-3.5 h-3.5 rounded-full border border-black/10 shrink-0"
                  :style="preset.code === 'system' ? (detectedSystemAccent ? { backgroundColor: detectedSystemAccent } : 'background: linear-gradient(135deg, #3584e4 50%, #2ecc71 50%)') : { backgroundColor: preset.hex }"
                />
                {{ t(`settings.accent_${preset.code}`) }}
              </button>
            </div>
          </div>
        </div>

        <div class="sec-label">{{ t("settings.sec_continuity") }}</div>
        <div class="rounded-[20px] border border-border bg-card overflow-hidden">
          <SettingsRow
            :icon="SolarHeadphones"
            :title="t('settings.smart_switch')"
            :desc="t('settings.smart_switch_hint')"
            :model-value="smartSwitchEnabled"
            @update:model-value="setSmartSwitch"
          />
          <SettingsRow
            divider
            :icon="SolarBell"
            :title="t('settings.notif_mirror')"
            :desc="t('settings.notif_mirror_hint')"
            :model-value="notifMirrorShow"
            @update:model-value="setNotifMirror"
          />
          <SettingsRow
            divider
            :icon="SolarBellBing"
            :title="t('settings.notif_send')"
            :desc="t('settings.notif_send_hint')"
            :model-value="notifMirrorSend"
            @update:model-value="setNotifSend"
          />
          <SettingsRow
            divider
            :icon="SolarClipboardList"
            :title="t('settings.clipboard_sync')"
            :desc="t('settings.clipboard_sync_hint')"
            :model-value="clipboardSync"
            @update:model-value="setClipboardSync"
          />
          <SettingsRow
            divider
            :icon="SolarFileDownload"
            :title="t('settings.file_auto_accept')"
            :desc="t('settings.file_auto_accept_hint')"
            :model-value="fileAutoAccept"
            @update:model-value="setFileAutoAccept"
          />
          <SettingsRow
            divider
            :icon="SolarCursor"
            :title="t('settings.uc')"
            :tag="t('mirror.experimental')"
            :desc="t('settings.uc_hint')"
            :model-value="ucEnabled"
            @update:model-value="setUniversalControl"
          />
          <p class="px-[18px] pb-4 -mt-1.5 text-[11.5px] leading-relaxed text-muted-foreground">
            {{ t("settings.uc_needs") }}
          </p>
          <p
            v-if="ucErrorText"
            class="px-[18px] pb-4 -mt-1 text-xs leading-relaxed text-destructive"
          >
            {{ ucErrorText }}
          </p>
          <div v-if="ucEnabled" class="px-[18px] py-4 border-t border-border/60">
            <div class="flex items-center gap-2.5 mb-3">
              <SolarCursor class="h-[18px] w-[18px] text-muted-foreground" :stroke-width="1.8" />
              <span class="text-sm font-semibold text-foreground">
                {{ t("settings.uc_placement") }}
              </span>
            </div>
            <div class="flex gap-2.5">
              <button
                v-for="e in UC_EDGES"
                :key="e.code"
                :class="pill(ucEdge === e.code)"
                @click="pickUcPlacement(e.code)"
              >
                {{ e.label }}
              </button>
            </div>
            <div class="mt-2 flex flex-wrap gap-2">
              <button
                v-for="e in UC_ENDS"
                :key="e.code || 'full'"
                :class="pill(ucEnd === e.code)"
                @click="pickUcEnd(e.code)"
              >
                {{ e.label }}
              </button>
            </div>
          </div>
        </div>

        <div class="sec-label">{{ t("settings.sec_privacy") }}</div>
        <div class="rounded-[20px] border border-border bg-card overflow-hidden">
          <SettingsRow
            :icon="SolarLock"
            :title="t('settings.proximity_lock')"
            :desc="t('settings.proximity_lock_hint')"
            :model-value="proximityAutoLock"
            @update:model-value="setProximityAutoLock"
          />
          <SettingsRow
            divider
            :icon="SolarUnlock"
            :title="t('settings.proximity_unlock')"
            :desc="t('settings.proximity_unlock_hint')"
            :model-value="proximityAutoUnlock"
            @update:model-value="setProximityAutoUnlock"
          />
        </div>

        <p class="text-center mt-7 text-[11.5px] text-muted-foreground/70">
          Vortex · {{ t("settings.footer") }}
        </p>
      </div>
    </main>
  </div>
</template>

<style scoped>
.sec-label {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 1.2px;
  text-transform: uppercase;
  color: hsl(var(--muted-foreground));
  margin: 26px 0 11px 4px;
}
</style>
