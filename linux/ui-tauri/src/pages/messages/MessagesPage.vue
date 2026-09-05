<script setup lang="ts">
import { computed, ref, watch, nextTick, onMounted, onUnmounted } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import { useVirtualList } from "@vueuse/core";
import { SolarChatSquare, SolarArrowLeft, SolarArrowDown, SolarSend, SolarLoader, SolarDangerCircle, SolarRestart, SolarTrash, SolarSmile, SolarPhone } from "@/lib/solarIcons";
import SearchInput from "@/components/SearchInput.vue";
import Avatar from "@/components/Avatar.vue";
import ConversationRow from "./ConversationRow.vue";
import { dial } from "@/lib/dial";
import { invoke } from "@tauri-apps/api/core";
import "emoji-picker-element";
import emojiDataUrl from "emoji-picker-element-data/en/emojibase/data.json?url";
import { theme } from "@/lib/theme";
import {
  sms,
  smsLoaded,
  localSent,
  history,
  threadPages,
  sendSms,
  resendSms,
  deleteLocalSent,
  markConversationRead,
  isMessageRead,
  loadThread,
  loadMoreThread,
  threadHasMore,
  threadCoveredByHistory,
  threadLoading,
  getDraft,
  setDraft,
  convKey,
  type SmsMessage,
} from "@/composables/useMessages";
import { contacts } from "@/composables/useContacts";

const { t } = useI18n();
const route = useRoute();
const router = useRouter();

const digits = (s: string) => s.replace(/\D/g, "").slice(-9);

const nameByDigits = computed(() => {
  const map = new Map<string, string>();
  for (const c of contacts.value) {
    for (const n of c.numbers) {
      const d = digits(n);
      if (d.length >= 7) map.set(d, c.name);
    }
  }
  return map;
});
function displayName(address: string): string {
  return nameByDigits.value.get(digits(address)) || address || t("messages.unknown");
}

interface Conversation {
  address: string;
  name: string;
  last: SmsMessage;
  count: number;
  unread: boolean;
  unreadCount: number;
}

const allMessages = computed(() => [...sms.value, ...history.value, ...localSent.value]);

const conversations = computed<Conversation[]>(() => {
  const byNum = new Map<string, SmsMessage[]>();
  const seen = new Set<string>();
  for (const m of allMessages.value) {
    const id = m.id || `${m.date}-${m.body}`;
    if (seen.has(id)) continue;
    seen.add(id);
    const k = convKey(m.address);
    (byNum.get(k) ?? byNum.set(k, []).get(k)!).push(m);
  }
  const list: Conversation[] = [];
  for (const msgs of byNum.values()) {
    msgs.sort((a, b) => a.date - b.date);
    const last = msgs[msgs.length - 1];
    const lastReceived = [...msgs].reverse().find((m) => m.type === 1);
    const unread = !!lastReceived && !isMessageRead(lastReceived);
    let unreadCount = 0;
    if (unread) {
      const lastReadAt = [...msgs]
        .reverse()
        .find((m) => m.type === 1 && isMessageRead(m))?.date ?? 0;
      unreadCount = msgs.filter(
        (m) => m.type === 1 && m.date > lastReadAt && !isMessageRead(m),
      ).length;
    }
    list.push({ address: last.address, name: displayName(last.address), last, count: msgs.length, unread, unreadCount });
  }
  list.sort((a, b) => b.last.date - a.last.date);
  return list;
});

const activeNumber = computed<string | null>(() => {
  const p = route.params.address;
  return typeof p === "string" && p ? decodeURIComponent(p) : null;
});
const draft = ref("");
const threadEl = ref<HTMLElement | null>(null);
const composerEl = ref<HTMLTextAreaElement | null>(null);

const emojiOpen = ref(false);
let emojiCloseTimer: number | null = null;
function emojiEnter() {
  if (emojiCloseTimer !== null) {
    clearTimeout(emojiCloseTimer);
    emojiCloseTimer = null;
  }
  emojiOpen.value = true;
}
function emojiLeave() {
  if (emojiCloseTimer !== null) clearTimeout(emojiCloseTimer);
  emojiCloseTimer = window.setTimeout(() => {
    emojiOpen.value = false;
    emojiCloseTimer = null;
  }, 250);
}
function onDocClick(e: MouseEvent) {
  if (!emojiOpen.value) return;
  if (!(e.target as HTMLElement).closest?.(".emoji-zone")) emojiOpen.value = false;
}
onMounted(() => document.addEventListener("click", onDocClick, true));
onUnmounted(() => document.removeEventListener("click", onDocClick, true));

function autoGrow() {
  const el = composerEl.value;
  if (!el) return;
  const border = el.offsetHeight - el.clientHeight;
  el.style.height = "auto";
  el.style.height = `${Math.min(el.scrollHeight + border, 112)}px`;
}
watch(draft, () => void nextTick(autoGrow));

const canReply = computed(() => {
  const a = activeNumber.value ?? "";
  return !!a && !/[a-zA-Z]/.test(a);
});

const isDark = computed(() => theme.value === "dark");
function onEmoji(e: Event) {
  const unicode = (e as CustomEvent).detail?.unicode as string | undefined;
  if (!unicode) return;
  const el = composerEl.value;
  if (el && typeof el.selectionStart === "number") {
    const pos = el.selectionStart;
    draft.value = draft.value.slice(0, pos) + unicode + draft.value.slice(el.selectionEnd);
    void nextTick(() => {
      el.selectionStart = el.selectionEnd = pos + unicode.length;
    });
  } else {
    draft.value += unicode;
  }
}

const activeName = computed(() => (activeNumber.value ? displayName(activeNumber.value) : ""));
const activeMessages = computed(() => {
  if (!activeNumber.value) return [];
  const d = convKey(activeNumber.value);
  const byId = new Map<string, SmsMessage>();
  const pages = threadPages.value[d] ?? [];
  for (const m of [...history.value, ...sms.value, ...pages, ...localSent.value]) {
    if (convKey(m.address) === d) byId.set(m.id || `${m.date}-${m.body}`, m);
  }
  return [...byId.values()].sort((a, b) => a.date - b.date);
});
const query = ref("");
const filteredConversations = computed(() => {
  const q = query.value.trim().toLowerCase();
  if (!q) return conversations.value;
  const qDigits = q.replace(/\D/g, "");
  const bodyHits = new Set<string>();
  for (const m of allMessages.value) {
    if (m.body.toLowerCase().includes(q)) bodyHits.add(convKey(m.address));
  }
  return conversations.value.filter(
    (c) =>
      c.name.toLowerCase().includes(q) ||
      (qDigits.length > 0 && c.address.replace(/\D/g, "").includes(qDigits)) ||
      bodyHits.has(convKey(c.address)),
  );
});

const CONV_ROW_H = 64;
const {
  list: convList,
  containerProps: convContainer,
  wrapperProps: convWrapper,
  scrollTo: convScrollTo,
} = useVirtualList(filteredConversations, { itemHeight: CONV_ROW_H, overscan: 6 });
watch(query, () => convScrollTo(0));
const TAIL_STEP = 60;
const tailCount = ref(TAIL_STEP);
const renderedMessages = computed(() => activeMessages.value.slice(-tailCount.value));
const localHasMore = computed(() => activeMessages.value.length > tailCount.value);

type ThreadItem = { kind: "date"; key: string; label: string } | { kind: "msg"; m: SmsMessage };
function dayLabel(ms: number): string {
  const d = new Date(ms);
  const today = new Date();
  const yest = new Date(today);
  yest.setDate(today.getDate() - 1);
  const same = (a: Date, b: Date) =>
    a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
  if (same(d, today)) return t("messages.today");
  if (same(d, yest)) return t("recents.yesterday");
  return d.toLocaleDateString(undefined, { day: "numeric", month: "long", year: d.getFullYear() === today.getFullYear() ? undefined : "numeric" });
}
const threadItems = computed<ThreadItem[]>(() => {
  const out: ThreadItem[] = [];
  let lastDay = "";
  for (const m of renderedMessages.value) {
    const day = new Date(m.date).toDateString();
    if (day !== lastDay) {
      lastDay = day;
      out.push({ kind: "date", key: `day-${day}`, label: dayLabel(m.date) });
    }
    out.push({ kind: "msg", m });
  }
  return out;
});

const activeCovered = computed(() =>
  activeNumber.value ? threadCoveredByHistory(activeNumber.value) : false,
);
const activeHasMore = computed(() => {
  if (!activeNumber.value) return false;
  return localHasMore.value || (!activeCovered.value && threadHasMore(activeNumber.value));
});
const activeLoading = computed(() => (activeNumber.value ? threadLoading(activeNumber.value) : false));

function threadFor(address: string): number {
  const d = convKey(address);
  return sms.value.find((m) => convKey(m.address) === d)?.thread ?? 0;
}
function open(address: string) {
  void router.push(`/messages/${encodeURIComponent(address)}`);
}
function back() {
  router.back();
}
watch(
  activeNumber,
  (address) => {
    if (!address) return;
    tailCount.value = TAIL_STEP;
    draft.value = getDraft(address);
    markConversationRead(address);
    if (!threadCoveredByHistory(address)) {
      void loadThread(address, threadFor(address));
    }
  },
  { immediate: true },
);
watch(draft, (text) => {
  if (activeNumber.value) setDraft(activeNumber.value, text);
});
watch(
  activeName,
  (name) => {
    void invoke("set_active_chat", { name: activeNumber.value ? name : "" });
  },
  { immediate: true },
);
onUnmounted(() => void invoke("set_active_chat", { name: "" }));
async function send() {
  const body = draft.value.trim();
  if (!body || !activeNumber.value) return;
  draft.value = "";
  await sendSms(activeNumber.value, body);
  await nextTick();
  threadEl.value?.scrollTo({ top: threadEl.value.scrollHeight });
}

watch(
  () => route.query.to,
  (to) => {
    if (typeof to === "string" && to) {
      void router.replace(`/messages/${encodeURIComponent(to)}`);
    }
  },
  { immediate: true },
);

const prependFromHeight = ref<number | null>(null);
const atBottom = ref(true);
function jumpToBottom() {
  const el = threadEl.value;
  if (el) el.scrollTo({ top: el.scrollHeight, behavior: "smooth" });
}
function onThreadScroll() {
  const el = threadEl.value;
  if (el) atBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 200;
  if (!el || !activeNumber.value || !activeHasMore.value) return;
  if (el.scrollTop <= 64) {
    prependFromHeight.value = el.scrollHeight;
    if (localHasMore.value) {
      tailCount.value += TAIL_STEP;
    } else if (!activeCovered.value) {
      void loadMoreThread(activeNumber.value, threadFor(activeNumber.value));
    } else {
      prependFromHeight.value = null;
    }
  }
}
watch(activeNumber, async (n) => {
  if (!n) return;
  await nextTick();
  const el = threadEl.value;
  if (el) el.scrollTop = el.scrollHeight;
  composerEl.value?.focus();
}, { immediate: true });

watch(renderedMessages, async (_now, prev) => {
  const el = threadEl.value;
  if (!el) return;
  const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 150;
  const wasEmpty = (prev?.length ?? 0) === 0;
  await nextTick();
  if (prependFromHeight.value !== null) {
    el.scrollTop = el.scrollHeight - prependFromHeight.value;
    prependFromHeight.value = null;
  } else if (nearBottom || wasEmpty) {
    el.scrollTo({ top: el.scrollHeight });
  }
});

function clockTime(ms: number): string {
  return new Date(ms).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}
function bubbleClass(m: SmsMessage): string {
  if (m.status === "failed") return "bg-rose-500 text-white rounded-br-sm";
  if (m.type === 2) return "bg-emerald-500 text-white dark:bg-emerald-900 dark:text-emerald-50 rounded-br-sm";
  return "bg-card border border-border dark:bg-accent dark:border-white/5 rounded-bl-sm";
}
</script>

<template>
  <div class="h-full flex flex-col">
    <template v-if="!activeNumber">
      <header class="flex items-center gap-2 px-5 py-4 border-b border-border bg-card/30">
        <SolarChatSquare class="h-5 w-5 text-muted-foreground" />
        <h1 class="text-base font-semibold">{{ t("messages.title") }}</h1>
        <span v-if="conversations.length" class="ml-auto text-xs text-muted-foreground">
          {{ conversations.length }}
        </span>
      </header>

      <div class="px-4 pt-3 pb-2">
        <SearchInput v-model="query" :placeholder="t('messages.search')" />
      </div>

      <main v-bind="convContainer" class="flex-1 min-h-0 overflow-y-auto">
        <div v-bind="convWrapper">
          <div
            v-for="{ data: c } in convList"
            :key="c.address"
            class="px-3 py-1"
            :style="{ height: CONV_ROW_H + 'px' }"
          >
            <ConversationRow :conv="c" @open="open" />
          </div>
        </div>

        <div
          v-if="smsLoaded && filteredConversations.length === 0"
          class="flex flex-col items-center justify-center text-center text-muted-foreground py-16"
        >
          <SolarChatSquare class="h-10 w-10 mb-3 opacity-40" />
          <p class="text-sm">{{ query ? t("recents.noResults") : t("messages.empty") }}</p>
        </div>
      </main>
    </template>

    <template v-else>
      <header class="flex items-center gap-2 px-3 py-3 border-b border-border bg-card/30">
        <button class="h-8 w-8 rounded-md flex items-center justify-center hover:bg-accent" @click="back">
          <SolarArrowLeft class="h-4 w-4" />
        </button>
        <Avatar :name="activeName" :size="28" />
        <h1 class="flex-1 text-sm font-semibold truncate">{{ activeName }}</h1>
        <button
          v-if="canReply && activeNumber"
          :title="t('contacts.call')"
          class="h-8 w-8 shrink-0 rounded-full flex items-center justify-center text-muted-foreground/60 hover:bg-emerald-500/15 hover:text-emerald-500 transition-colors"
          @click="dial(activeNumber)"
        >
          <SolarPhone class="h-4 w-4" />
        </button>
      </header>

      <div class="relative flex-1 min-h-0 flex flex-col">
      <button
        v-if="!atBottom"
        class="absolute bottom-3 right-3 z-10 h-9 w-9 rounded-full bg-card border border-border shadow-lg flex items-center justify-center text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
        @click="jumpToBottom"
      >
        <SolarArrowDown class="h-4 w-4" />
      </button>
      <main ref="threadEl" class="flex-1 min-h-0 overflow-y-auto p-3 space-y-2 flex flex-col" @scroll.passive="onThreadScroll">
        <div v-if="activeLoading" class="flex justify-center py-1 shrink-0">
          <SolarLoader class="h-4 w-4 animate-spin text-muted-foreground/60" />
        </div>
        <template v-for="item in threadItems" :key="item.kind === 'date' ? item.key : item.m.id">
          <div v-if="item.kind === 'date'" class="self-center shrink-0 my-1">
            <span class="text-xs text-muted-foreground bg-card dark:bg-accent/60 border border-border/50 rounded-full px-3 py-1">
              {{ item.label }}
            </span>
          </div>

          <div
            v-else
            class="flex items-center gap-1.5 max-w-[80%]"
            :class="item.m.type === 2 ? 'self-end' : 'self-start'"
          >
          <template v-if="item.m.status === 'failed'">
            <button
              :title="t('messages.resend')"
              class="h-7 w-7 shrink-0 rounded-full flex items-center justify-center text-muted-foreground hover:bg-accent hover:text-emerald-500 transition-colors"
              @click="resendSms(item.m.id)"
            >
              <SolarRestart class="h-3.5 w-3.5" />
            </button>
            <button
              :title="t('messages.delete')"
              class="h-7 w-7 shrink-0 rounded-full flex items-center justify-center text-muted-foreground hover:bg-accent hover:text-rose-500 transition-colors"
              @click="deleteLocalSent(item.m.id)"
            >
              <SolarTrash class="h-3.5 w-3.5" />
            </button>
          </template>

          <div
            class="relative rounded-2xl px-3 py-2 min-w-0"
            :class="[bubbleClass(item.m), item.m.status === 'sending' ? 'opacity-70' : '']"
          >
            <template v-if="!item.m.status">
              <p class="text-sm whitespace-pre-wrap break-words pb-1.5 selectable-text">{{ item.m.body }}<span class="inline-block w-12" /></p>
              <span class="absolute bottom-1 right-3 text-[10px] opacity-70">{{ clockTime(item.m.date) }}</span>
            </template>
            <template v-else>
              <p class="text-sm whitespace-pre-wrap break-words selectable-text">{{ item.m.body }}</p>
              <p class="text-[10px] mt-0.5 opacity-70 text-right flex items-center justify-end gap-1">
                <SolarLoader v-if="item.m.status === 'sending'" class="h-3 w-3 animate-spin" />
                <SolarDangerCircle v-else class="h-3 w-3" />
                <span>{{ item.m.status === "sending" ? t("messages.sending") : t("messages.failed") }}</span>
              </p>
            </template>
          </div>
          </div>
        </template>
        <div v-if="activeMessages.length === 0" class="text-center text-xs text-muted-foreground py-8">
          {{ t("messages.start") }}
        </div>
      </main>
      </div>

      <div
        v-if="!canReply"
        class="border-t border-border bg-card/30 px-4 py-3 text-center text-xs text-muted-foreground"
      >
        {{ t("messages.noReply") }}
      </div>

      <div v-else class="relative border-t border-border bg-card/30">
        <div
          v-if="emojiOpen"
          class="emoji-zone absolute bottom-full right-2 mb-2 z-10 rounded-xl overflow-hidden shadow-xl border border-border bg-card"
          @mouseenter="emojiEnter"
          @mouseleave="emojiLeave"
        >
          <emoji-picker
            :class="isDark ? 'dark' : 'light'"
            :data-source="emojiDataUrl"
            style="
              width: min(92vw, 340px);
              height: 400px;
              --background: hsl(var(--card));
              --border-color: hsl(var(--border));
              --indicator-color: hsl(var(--primary));
              --border-radius: 0;
              --input-border-color: hsl(var(--border));
              --input-border-size: 1px;
              --input-border-radius: 0.5rem;
              --input-padding: 0.5rem 0.75rem;
              --input-font-size: 0.875rem;
              --input-line-height: 1.25rem;
              --input-font-color: hsl(var(--foreground));
              --input-placeholder-color: hsl(var(--muted-foreground));
              --outline-color: hsl(var(--ring));
              --outline-size: 2px;
            "
            @emoji-click="onEmoji"
          />
        </div>
        <form class="flex items-end gap-2 p-3" @submit.prevent="send">
          <textarea
            ref="composerEl"
            v-model="draft"
            rows="1"
            :placeholder="t('messages.compose')"
            class="flex-1 resize-none max-h-28 rounded-xl border border-border bg-muted/50 px-3.5 py-2.5 text-[13.5px] outline-none placeholder:text-muted-foreground transition-colors focus:border-primary"
            @keydown.enter.exact.prevent="send"
          />
          <button
            type="button"
            :title="t('messages.emoji')"
            class="emoji-zone h-9 w-9 shrink-0 rounded-full flex items-center justify-center text-muted-foreground hover:bg-accent hover:text-amber-500 transition-colors"
            :class="emojiOpen ? 'bg-accent text-amber-500' : ''"
            @mouseenter="emojiEnter"
            @mouseleave="emojiLeave"
            @click="emojiOpen = !emojiOpen"
          >
            <SolarSmile class="h-5 w-5" />
          </button>
          <button
            type="submit"
            :disabled="!draft.trim()"
            class="h-9 w-9 shrink-0 rounded-full flex items-center justify-center bg-emerald-500 text-white disabled:opacity-40 hover:bg-emerald-600 transition-colors"
          >
            <SolarSend class="h-4 w-4" />
          </button>
        </form>
      </div>
    </template>
  </div>
</template>
