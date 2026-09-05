import { ref, watchEffect } from "vue";

export type Theme = "light" | "dark";
export type ThemePreference = "system" | "light" | "dark";

const STORAGE_KEY = "vortex.theme";

const mediaQuery =
  typeof window !== "undefined" && window.matchMedia
    ? window.matchMedia("(prefers-color-scheme: dark)")
    : null;

function getSystemTheme(): Theme {
  return mediaQuery && mediaQuery.matches ? "dark" : "light";
}

function detectPreference(): ThemePreference {
  const stored =
    typeof localStorage !== "undefined"
      ? (localStorage.getItem(STORAGE_KEY) as ThemePreference | null)
      : null;
  if (stored === "system" || stored === "light" || stored === "dark") return stored;
  return "system";
}

export const themePreference = ref<ThemePreference>(detectPreference());

function resolveTheme(pref: ThemePreference): Theme {
  if (pref === "system") return getSystemTheme();
  return pref;
}

export const theme = ref<Theme>(resolveTheme(themePreference.value));

if (mediaQuery) {
  mediaQuery.addEventListener("change", () => {
    if (themePreference.value === "system") {
      theme.value = getSystemTheme();
    }
  });
}

watchEffect(() => {
  theme.value = resolveTheme(themePreference.value);
  if (typeof document !== "undefined") {
    const root = document.documentElement;
    if (theme.value === "dark") root.classList.add("dark");
    else root.classList.remove("dark");
  }
  if (typeof localStorage !== "undefined") {
    localStorage.setItem(STORAGE_KEY, themePreference.value);
  }
});

export function setThemePreference(pref: ThemePreference) {
  themePreference.value = pref;
}

export function toggleTheme() {
  const current = theme.value;
  setThemePreference(current === "dark" ? "light" : "dark");
}
