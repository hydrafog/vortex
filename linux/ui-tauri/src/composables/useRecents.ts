import { computed, ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

export interface CallLogEntry {
  id: string;
  number: string;
  name: string;
  type: number;
  date: number;
  duration: number;
}

export const callLog = ref<CallLogEntry[]>([]);
export const callLogLoaded = ref(false);

export const callHistory = ref<CallLogEntry[]>([]);

export const allCalls = computed<CallLogEntry[]>(() => {
  const byId = new Map<string, CallLogEntry>();
  for (const e of [...callHistory.value, ...callLog.value]) {
    byId.set(e.id || `${e.date}-${e.number}`, e);
  }
  return [...byId.values()].sort((a, b) => b.date - a.date);
});

let started = false;

export async function initRecents(): Promise<void> {
  if (started) return;
  started = true;

  try {
    callLog.value = await invoke<CallLogEntry[]>("get_call_log");
    callLogLoaded.value = true;
  } catch {
  }
  try {
    callHistory.value = await invoke<CallLogEntry[]>("get_call_log_history");
  } catch {
  }

  await listen<CallLogEntry[]>("vortex:call_log", (e) => {
    callLog.value = e.payload ?? [];
    callLogLoaded.value = true;
  });

  await listen<CallLogEntry[]>("vortex:call-log-history", (e) => {
    callHistory.value = e.payload ?? [];
  });
}
