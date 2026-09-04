import { createI18n } from "vue-i18n";
import { invoke } from "@tauri-apps/api/core";
import en from "./locales/en.json";
import uz from "./locales/uz.json";
import ru from "./locales/ru.json";

export type LocaleCode = "en" | "uz" | "ru";

const STORAGE_KEY = "vortex.locale";
const STORAGE_KEY_TS = "vortex.locale_changed_at";

function detect(): { code: LocaleCode; changedAt: number } {
  const stored = localStorage.getItem(STORAGE_KEY) as LocaleCode | null;
  const storedTs = parseInt(localStorage.getItem(STORAGE_KEY_TS) ?? "0", 10) || 0;
  if (stored && (stored === "en" || stored === "uz" || stored === "ru")) {
    return { code: stored, changedAt: storedTs };
  }
  const lang = (navigator.language || "en").toLowerCase();
  if (lang.startsWith("uz")) return { code: "uz", changedAt: 0 };
  if (lang.startsWith("ru")) return { code: "ru", changedAt: 0 };
  return { code: "en", changedAt: 0 };
}

const initial = detect();

function syncVoiceLang(loc: LocaleCode) {
  invoke("set_voice_lang", { code: loc }).catch(() => {});
}

export const i18n = createI18n({
  legacy: false,
  locale: initial.code,
  fallbackLocale: "en",
  messages: { en, uz, ru },
});

syncVoiceLang(initial.code);

let currentChangedAt: number = initial.changedAt;

export function currentLocale(): LocaleCode {
  return i18n.global.locale.value as LocaleCode;
}

export function currentLocaleChangedAt(): number {
  return currentChangedAt;
}

export function setLocale(loc: LocaleCode) {
  i18n.global.locale.value = loc;
  currentChangedAt = Math.floor(Date.now() / 1000);
  localStorage.setItem(STORAGE_KEY, loc);
  localStorage.setItem(STORAGE_KEY_TS, String(currentChangedAt));
  syncVoiceLang(loc);
}

export function adoptPeerLocale(loc: LocaleCode, changedAt: number) {
  if (changedAt <= currentChangedAt) return;
  i18n.global.locale.value = loc;
  currentChangedAt = changedAt;
  localStorage.setItem(STORAGE_KEY, loc);
  localStorage.setItem(STORAGE_KEY_TS, String(changedAt));
  syncVoiceLang(loc);
}

export const LOCALES: { code: LocaleCode; label: string }[] = [
  { code: "uz", label: "O'zbekcha" },
  { code: "en", label: "English" },
  { code: "ru", label: "Русский" },
];
