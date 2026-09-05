<script setup lang="ts">
import { onMounted, onUnmounted, ref, computed, watch, nextTick } from "vue";
import { useI18n } from "vue-i18n";
import { useVirtualList } from "@vueuse/core";
import { invoke, convertFileSrc } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import {
  SolarPin,
  SolarPinSlash,
  SolarTrash,
  SolarClipboardList,
  SolarSearch,
  SolarGallery as ImageIcon,
  SolarText,
  SolarClock,
  SolarHardDrive,
} from "@/lib/solarIcons";

interface ClipEntry {
  id: string;
  kind: "text" | "image";
  text: string | null;
  path: string | null;
  bytes: number;
  ts_ms: number;
  pinned: boolean;
}

const TEXT_ROW_H = 52;
const IMG_ROW_H = 76;
function rowH(e: ClipEntry | undefined): number {
  return e?.kind === "image" ? IMG_ROW_H : TEXT_ROW_H;
}

const { t } = useI18n();
const entries = ref<ClipEntry[]>([]);
const filter = ref("");
const selected = ref(0);
const inputEl = ref<HTMLInputElement | null>(null);
const previewEl = ref<HTMLElement | null>(null);
const full = ref<ClipEntry | null>(null);

function haystack(e: ClipEntry): string {
  if (e.kind === "text") return (e.text ?? "").toLowerCase();
  const kb = `${Math.round(e.bytes / 1024)}kb`;
  const d = new Date(e.ts_ms);
  const date = `${d.toLocaleDateString(undefined, { day: "numeric", month: "short" })} ${d.toLocaleDateString()}`;
  return `image rasm ${kb} ${date}`.toLowerCase();
}

const visible = computed(() => {
  const q = filter.value.trim().toLowerCase();
  if (!q) return entries.value;
  return entries.value.filter((e) => haystack(e).includes(q));
});

const {
  list: vlist,
  containerProps,
  wrapperProps,
  scrollTo: vScrollTo,
} = useVirtualList(visible, {
  itemHeight: (i) => rowH(visible.value[i]),
  overscan: 6,
});

const current = computed<ClipEntry | undefined>(() => visible.value[selected.value]);

function needsPreview(e: ClipEntry | undefined): boolean {
  if (!e) return false;
  if (e.kind === "image") return true;
  const txt = e.text ?? "";
  return txt.length > 40 || txt.includes("\n");
}

const showPreview = computed(() => needsPreview(current.value));

function fmtSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function fmtDate(ms: number): string {
  return new Date(ms).toLocaleString(undefined, {
    day: "numeric",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

async function syncPreview() {
  const need = showPreview.value;
  void invoke("clipboard_set_preview", { visible: need }).catch(() => {});
  if (!need) {
    full.value = null;
    return;
  }
  const e = current.value;
  if (!e) return;
  try {
    full.value = await invoke<ClipEntry | null>("clipboard_get", { id: e.id });
  } catch {
    full.value = e;
  }
  void nextTick(() => previewEl.value?.scrollTo({ top: 0 }));
}

watch([filter, entries], () => {
  selected.value = 0;
});
watch(current, syncPreview);

async function refresh() {
  try {
    entries.value = await invoke<ClipEntry[]>("clipboard_history");
  } catch {
  }
}

async function pick(e: ClipEntry | undefined) {
  if (!e) return;
  await invoke("clipboard_select", { id: e.id }).catch(() => {});
  hide();
}

async function togglePin(e: ClipEntry, ev: Event) {
  ev.stopPropagation();
  await invoke("clipboard_pin", { id: e.id, pinned: !e.pinned }).catch(() => {});
  await refresh();
}

async function remove(e: ClipEntry, ev: Event) {
  ev.stopPropagation();
  await invoke("clipboard_delete", { id: e.id }).catch(() => {});
  await refresh();
}

function resetScroll() {
  vScrollTo(0);
}

function hide() {
  filter.value = "";
  selected.value = 0;
  resetScroll();
  void invoke("clipboard_hide");
}

function offsetOf(idx: number): number {
  const arr = visible.value;
  let top = 0;
  for (let i = 0; i < idx && i < arr.length; i++) top += rowH(arr[i]);
  return top;
}

function scrollToSelected() {
  const c = containerProps.ref.value;
  const cur = visible.value[selected.value];
  if (!c || !cur) return;
  const top = offsetOf(selected.value);
  const bottom = top + rowH(cur);
  if (top < c.scrollTop) c.scrollTop = top;
  else if (bottom > c.scrollTop + c.clientHeight) c.scrollTop = bottom - c.clientHeight;
}

function pageSize(): number {
  const c = containerProps.ref.value;
  const h = c ? c.clientHeight : 400;
  return Math.max(1, Math.floor(h / TEXT_ROW_H) - 1);
}

function move(delta: number) {
  const n = visible.value.length;
  if (!n) return;
  selected.value = (selected.value + delta + n) % n;
  scrollToSelected();
}

function moveTo(idx: number) {
  const n = visible.value.length;
  if (!n) return;
  selected.value = Math.max(0, Math.min(idx, n - 1));
  scrollToSelected();
}

let lastX = -1;
let lastY = -1;
function onHover(i: number, e: MouseEvent) {
  if (e.clientX === lastX && e.clientY === lastY) return;
  lastX = e.clientX;
  lastY = e.clientY;
  selected.value = i;
}

function clearPageBackground() {
  for (const el of [document.documentElement, document.body, document.getElementById("app")]) {
    if (el) (el as HTMLElement).style.setProperty("background", "transparent", "important");
  }
}

async function rearm() {
  selected.value = 0;
  filter.value = "";
  resetScroll();
  await refresh();
  selected.value = 0;
  await syncPreview();
  await nextTick();
  resetScroll();
  focusSearch();
  void invoke("clipboard_capture_now").catch(() => {});
}

function focusSearch() {
  inputEl.value?.focus();
  setTimeout(() => inputEl.value?.focus(), 70);
  setTimeout(() => inputEl.value?.focus(), 220);
}

function onKey(e: KeyboardEvent) {
  const ctrl = e.ctrlKey || e.metaKey;
  const n = visible.value.length;
  if (e.key === "Escape") {
    e.preventDefault();
    hide();
  } else if (e.key === "ArrowDown") {
    e.preventDefault();
    ctrl ? moveTo(n - 1) : move(1);
  } else if (e.key === "ArrowUp") {
    e.preventDefault();
    ctrl ? moveTo(0) : move(-1);
  } else if (e.key === "ArrowRight") {
    e.preventDefault();
    moveTo(selected.value + pageSize());
  } else if (e.key === "ArrowLeft") {
    e.preventDefault();
    moveTo(selected.value - pageSize());
  } else if (e.key === "Enter") {
    e.preventDefault();
    void pick(visible.value[selected.value]);
  }
}

let unlisten: UnlistenFn | null = null;
let unshown: UnlistenFn | null = null;
onMounted(async () => {
  clearPageBackground();
  (window as unknown as { __vortexRearm?: () => void }).__vortexRearm = () => {
    void rearm();
  };
  await rearm();
  window.addEventListener("keydown", onKey);
  window.addEventListener("focus", focusSearch);
  unlisten = await listen("vortex:clipboard", refresh);
  unshown = await listen("vortex:clipboard-shown", rearm);
});
onUnmounted(() => {
  window.removeEventListener("keydown", onKey);
  window.removeEventListener("focus", focusSearch);
  unlisten?.();
  unshown?.();
});
</script>

<template>
  <div
    class="h-screen w-screen flex bg-background text-foreground overflow-hidden border border-border rounded-2xl"
  >
    <div class="flex flex-col w-[460px] shrink-0 min-w-0 min-h-0 border-r border-border">
      <header class="flex items-center gap-2 px-4 py-2.5 border-b border-border bg-card shrink-0">
        <SolarClipboardList class="h-4 w-4 text-muted-foreground" />
        <span class="text-sm font-semibold flex-1">{{ t("clipboard.title") }}</span>
        <span class="text-[11px] text-muted-foreground">{{ entries.length }}</span>
      </header>

      <div class="px-4 pt-3 pb-2 shrink-0 border-b border-border/50">
        <div class="flex items-center gap-2 h-10 px-3 rounded-lg border border-border bg-card">
          <SolarSearch class="h-4 w-4 text-muted-foreground/70 shrink-0" />
          <input
            ref="inputEl"
            v-model="filter"
            :placeholder="t('clipboard.search')"
            class="flex-1 bg-transparent text-sm outline-none placeholder:text-muted-foreground/50"
          />
        </div>
      </div>

      <main v-bind="containerProps" class="flex-1 min-h-0 overflow-y-auto">
        <p v-if="visible.length === 0" class="text-xs text-muted-foreground text-center py-8">
          {{ t("clipboard.empty") }}
        </p>
        <div v-else v-bind="wrapperProps">
          <div
            v-for="{ data: e, index: i } in vlist"
            :key="e.id"
            class="px-4 py-1"
            :style="{ height: rowH(e) + 'px' }"
          >
            <button
              class="group h-full w-full text-left rounded-lg border bg-card px-3 relative overflow-hidden flex items-center"
              :class="i === selected
                ? 'border-primary ring-1 ring-primary/60 bg-accent'
                : 'border-border hover:bg-accent/60'"
              @click="pick(e)"
              @mousemove="onHover(i, $event)"
            >
              <div v-if="e.kind === 'image' && e.path" class="flex items-center gap-2.5 w-full">
                <img
                  :src="convertFileSrc(e.path)"
                  class="rounded object-contain max-h-[58px] max-w-[104px] w-auto h-auto shrink-0 border border-border/50 bg-background"
                  loading="lazy"
                />
                <span class="text-[12px] text-muted-foreground flex items-center gap-1.5">
                  <ImageIcon class="h-3.5 w-3.5 shrink-0" />
                  {{ t("clipboard.image") }} · {{ fmtSize(e.bytes) }}
                </span>
              </div>
              <p
                v-else
                class="text-sm truncate pr-10"
              >{{ e.text }}</p>
              <span
                class="absolute bottom-1.5 right-2 opacity-0 group-hover:opacity-100 transition-opacity flex items-center gap-1.5"
                :class="{ 'opacity-100': e.pinned }"
              >
                <component
                  :is="e.pinned ? SolarPinSlash : SolarPin"
                  class="h-4 w-4 text-muted-foreground hover:text-foreground"
                  :class="{ 'text-amber-500': e.pinned }"
                  @click="togglePin(e, $event)"
                />
                <SolarTrash
                  class="h-4 w-4 text-muted-foreground hover:text-red-500"
                  @click="remove(e, $event)"
                />
              </span>
            </button>
          </div>
        </div>
      </main>
    </div>

    <aside v-if="showPreview && full" class="flex-1 min-w-0 flex flex-col bg-background">
      <div
        ref="previewEl"
        class="flex-1 min-h-0 overflow-auto p-4 flex justify-center"
        :class="full.kind === 'image' ? 'items-center' : 'items-start'"
      >
        <img
          v-if="full.kind === 'image' && full.path"
          :src="convertFileSrc(full.path)"
          class="w-full h-auto max-h-full rounded-lg border border-border object-contain"
        />
        <p
          v-else
          class="w-full text-xs leading-relaxed whitespace-pre-wrap [overflow-wrap:anywhere] font-mono"
        >{{ full.text }}</p>
      </div>

      <footer
        class="shrink-0 border-t border-border px-5 py-3 flex items-center gap-4 text-[12px] text-muted-foreground bg-card/40"
      >
        <span class="flex items-center gap-1.5">
          <component :is="full.kind === 'image' ? ImageIcon : SolarText" class="h-3.5 w-3.5" />
          {{ full.kind === "image" ? t("clipboard.image") : `${(full.text ?? '').length} ${t("clipboard.chars")}` }}
        </span>
        <span class="flex items-center gap-1.5">
          <SolarHardDrive class="h-3.5 w-3.5" />
          {{ fmtSize(full.bytes) }}
        </span>
        <span class="flex items-center gap-1.5 ml-auto whitespace-nowrap">
          <SolarClock class="h-3.5 w-3.5" />
          {{ fmtDate(full.ts_ms) }}
        </span>
      </footer>
    </aside>
  </div>
</template>
