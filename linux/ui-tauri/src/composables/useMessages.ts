import { computed, ref, reactive } from "vue";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

export interface SmsMessage {
  id: string;
  address: string;
  body: string;
  type: number;
  date: number;
  thread: number;
  read: number;
  status?: "sending" | "failed";
  hardFail?: boolean;
  autoRetried?: boolean;
}

export const sms = ref<SmsMessage[]>([]);
export const smsLoaded = ref(false);
export const localSent = ref<SmsMessage[]>([]);

export const threadPages = ref<Record<string, SmsMessage[]>>({});

const PAGE = 40;

interface Cursor {
  offset: number;
  limit: number;
  done: boolean;
  loading: boolean;
}
const cursors = reactive(new Map<string, Cursor>());
let lastRequested: string | null = null;

export const convKey = (s: string) => (s || "").replace(/\D/g, "").slice(-9) || (s || "").trim();

export const history = ref<SmsMessage[]>([]);


let started = false;

export async function initMessages(): Promise<void> {
  if (started) return;
  started = true;

  try {
    sms.value = await invoke<SmsMessage[]>("get_sms");
    smsLoaded.value = true;
  } catch {
  }
  try {
    history.value = await invoke<SmsMessage[]>("get_sms_history");
  } catch {
  }

  await listen<SmsMessage[]>("vortex:sms", (e) => {
    sms.value = e.payload ?? [];
    smsLoaded.value = true;
    reconcilePending();
  });

  await listen<SmsMessage[]>("vortex:sms-history", (e) => {
    history.value = e.payload ?? [];
    reconcilePending();
  });

  await listen<SmsMessage[]>("vortex:sms-thread", (e) => {
    mergeThreadPage(e.payload ?? []);
  });

  await listen("vortex:peer_state", () => {
    for (const m of localSent.value) {
      if (m.status === "failed" && m.hardFail && !m.autoRetried) {
        m.autoRetried = true;
        void resendSms(m.id);
      }
    }
  });

  await listen<{ title: string; appId: string; kind?: string }>("vortex:open-chat", async (e) => {
    const kind = e.payload?.kind ?? "";
    const { router } = await import("@/router");
    if (kind === "call") {
      void router.push("/recents");
      return;
    }
    if (kind !== "sms") return;
    const title = (e.payload?.title ?? "").trim();
    if (!title) return;
    const { contacts } = await import("@/composables/useContacts");
    const lower = title.toLowerCase();
    const byContact = contacts.value.find((c) => c.name.trim().toLowerCase() === lower);
    const known = (addr: string) =>
      [...sms.value, ...history.value].some((m) => convKey(m.address) === convKey(addr));
    let target: string | null = null;
    if (byContact?.numbers[0]) {
      target = byContact.numbers[0];
    } else if (known(title)) {
      target = title;
    }
    if (target) {
      void router.push(`/messages/${encodeURIComponent(target)}`);
    }
  });

  await listen<{ number: string }>("vortex:open-sms", async (e) => {
    const number = (e.payload?.number ?? "").trim();
    if (!number) return;
    const { router } = await import("@/router");
    void router.push(`/messages/${encodeURIComponent(number)}`);
  });
}

function mergeThreadPage(messages: SmsMessage[]): void {
  if (messages.length === 0) {
    if (lastRequested) {
      const cur = cursors.get(lastRequested);
      if (cur) {
        cur.done = true;
        cur.loading = false;
      }
    }
    return;
  }
  const d = convKey(messages[0].address);
  const existing = threadPages.value[d] ?? [];
  const byId = new Map(existing.map((m) => [m.id, m]));
  for (const m of messages) byId.set(m.id, m);
  threadPages.value = { ...threadPages.value, [d]: [...byId.values()] };
  const cur = cursors.get(d);
  if (cur) {
    cur.offset += messages.length;
    cur.loading = false;
    if (messages.length < cur.limit) cur.done = true;
  }
}

const PAGE_TIMEOUT_MS = 12_000;

function armPageTimeout(d: string): void {
  const offsetAtSend = cursors.get(d)?.offset ?? 0;
  window.setTimeout(() => {
    const cur = cursors.get(d);
    if (!cur || !cur.loading || cur.offset !== offsetAtSend) return;
    cur.loading = false;
    if (cur.offset === 0) cursors.delete(d);
  }, PAGE_TIMEOUT_MS);
}

export async function loadThread(address: string, thread: number): Promise<void> {
  const d = convKey(address);
  if (cursors.has(d)) return;
  cursors.set(d, { offset: 0, limit: PAGE, done: false, loading: true });
  lastRequested = d;
  try {
    await invoke("load_sms_thread", { thread: thread || 0, number: address, offset: 0, limit: PAGE });
    armPageTimeout(d);
  } catch (e) {
    console.warn("load_sms_thread failed", e);
    cursors.delete(d);
  }
}

export async function loadMoreThread(address: string, thread: number): Promise<void> {
  const d = convKey(address);
  const cur = cursors.get(d);
  if (!cur) return loadThread(address, thread);
  if (cur.loading || cur.done) return;
  cur.loading = true;
  cur.limit = PAGE;
  lastRequested = d;
  try {
    await invoke("load_sms_thread", { thread: thread || 0, number: address, offset: cur.offset, limit: PAGE });
    armPageTimeout(d);
  } catch (e) {
    console.warn("load_sms_thread (more) failed", e);
    cur.loading = false;
  }
}

export function threadHasMore(address: string): boolean {
  const cur = cursors.get(convKey(address));
  return !cur || !cur.done;
}

export function threadCoveredByHistory(address: string): boolean {
  const d = convKey(address);
  return history.value.some((m) => convKey(m.address) === d);
}

export function threadLoading(address: string): boolean {
  return cursors.get(convKey(address))?.loading ?? false;
}

function reconcilePending(): void {
  if (!localSent.value.length) return;
  const confirmed = (l: SmsMessage) =>
    [...sms.value, ...history.value].some(
      (m) => m.type === 2 && convKey(m.address) === convKey(l.address) && m.body === l.body,
    );
  localSent.value = localSent.value.filter((l) => !confirmed(l));
}

async function fire(id: string, to: string, body: string): Promise<void> {
  try {
    await invoke("send_sms", { number: to, body });
    const e = localSent.value.find((x) => x.id === id);
    if (e) e.status = undefined;
  } catch (err) {
    console.warn("send_sms failed", err);
    const e = localSent.value.find((x) => x.id === id);
    if (e) {
      e.status = "failed";
      e.hardFail = true;
    }
  }
}

export async function sendSms(to: string, body: string): Promise<void> {
  const t = (to ?? "").trim();
  const b = (body ?? "").trim();
  if (!t || !b) return;
  const id = `local-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
  localSent.value = [
    ...localSent.value,
    { id, address: t, body: b, type: 2, date: Date.now(), thread: 0, read: 1, status: "sending" },
  ];
  await fire(id, t, b);
  armConfirmTimeout(id);
}

const SEND_CONFIRM_TIMEOUT_MS = 60_000;
function armConfirmTimeout(id: string): void {
  window.setTimeout(() => {
    const e = localSent.value.find((x) => x.id === id);
    if (e && e.status === undefined) {
      console.warn("send not confirmed by sync; marking failed");
      e.status = "failed";
    }
  }, SEND_CONFIRM_TIMEOUT_MS);
}

export async function resendSms(id: string): Promise<void> {
  const entry = localSent.value.find((x) => x.id === id);
  if (!entry) return;
  entry.status = "sending";
  entry.date = Date.now();
  await fire(id, entry.address, entry.body);
  armConfirmTimeout(id);
}

export function deleteLocalSent(id: string): void {
  localSent.value = localSent.value.filter((x) => x.id !== id);
}

const READ_KEY = "vortex.sms.readWatermark";
function loadWatermark(): Record<string, number> {
  try {
    return JSON.parse(localStorage.getItem(READ_KEY) || "{}");
  } catch {
    return {};
  }
}
export const readWatermark = ref<Record<string, number>>(loadWatermark());

export function isMessageRead(m: SmsMessage): boolean {
  if (m.read !== 0) return true;
  return m.date <= (readWatermark.value[convKey(m.address)] ?? 0);
}

export const unreadConversations = computed(() => {
  const latestIn = new Map<string, SmsMessage>();
  for (const m of [...sms.value, ...history.value]) {
    if (m.type !== 1) continue;
    const k = convKey(m.address);
    const cur = latestIn.get(k);
    if (!cur || m.date > cur.date) latestIn.set(k, m);
  }
  let n = 0;
  for (const m of latestIn.values()) if (!isMessageRead(m)) n++;
  return n;
});

const DRAFTS_KEY = "vortex.sms.drafts";
function loadDrafts(): Record<string, string> {
  try {
    return JSON.parse(localStorage.getItem(DRAFTS_KEY) || "{}");
  } catch {
    return {};
  }
}
const draftsStore = ref<Record<string, string>>(loadDrafts());
export function getDraft(address: string): string {
  return draftsStore.value[convKey(address)] ?? "";
}
export function setDraft(address: string, text: string): void {
  const k = convKey(address);
  const next = { ...draftsStore.value };
  if (text) next[k] = text;
  else delete next[k];
  draftsStore.value = next;
  try {
    localStorage.setItem(DRAFTS_KEY, JSON.stringify(next));
  } catch {
  }
}

export function markConversationRead(address: string): void {
  const k = convKey(address);
  readWatermark.value = { ...readWatermark.value, [k]: Date.now() };
  try {
    localStorage.setItem(READ_KEY, JSON.stringify(readWatermark.value));
  } catch {
  }
}
