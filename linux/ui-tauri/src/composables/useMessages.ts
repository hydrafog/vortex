import { ref } from "vue";
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
}

export const sms = ref<SmsMessage[]>([]);
export const smsLoaded = ref(false);

export const history = ref<SmsMessage[]>([]);

export const convKey = (s: string) => (s || "").replace(/\D/g, "").slice(-9) || (s || "").trim();

let started = false;

export async function initMessages(): Promise<void> {
  if (started) return;
  started = true;

  try {
    sms.value = await invoke<SmsMessage[]>("get_sms");
    smsLoaded.value = true;
  } catch (e) {
    console.warn("get_sms failed", e);
  }
  try {
    history.value = await invoke<SmsMessage[]>("get_sms_history");
  } catch (e) {
    console.warn("get_sms_history failed", e);
  }

  await listen<SmsMessage[]>("vortex:sms", (e) => {
    sms.value = e.payload ?? [];
    smsLoaded.value = true;
  });

  await listen<SmsMessage[]>("vortex:sms-history", (e) => {
    history.value = e.payload ?? [];
  });
}
