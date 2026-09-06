import { ref, watchEffect } from "vue";
import { getSystemAccentColor } from "./bridge";

export type Theme = "light" | "dark";
export type ThemePreference = "system" | "light" | "dark";

export type AccentColor =
  | "system"
  | "vortex"
  | "blue"
  | "teal"
  | "green"
  | "yellow"
  | "orange"
  | "red"
  | "pink"
  | "purple"
  | "slate";

export const ACCENT_PRESETS: { code: AccentColor; name: string; hex: string }[] = [
  { code: "system", name: "System (GTK)", hex: "var(--primary)" },
  { code: "vortex", name: "Vortex Green", hex: "#2ecc71" },
  { code: "blue", name: "Blue", hex: "#3584e4" },
  { code: "teal", name: "Teal", hex: "#21a48c" },
  { code: "green", name: "Green", hex: "#33d17a" },
  { code: "yellow", name: "Yellow", hex: "#f6d32d" },
  { code: "orange", name: "Orange", hex: "#ff7800" },
  { code: "red", name: "Red", hex: "#e01b24" },
  { code: "pink", name: "Pink", hex: "#f04494" },
  { code: "purple", name: "Purple", hex: "#9141ac" },
  { code: "slate", name: "Slate", hex: "#737e8c" },
];

const STORAGE_KEY = "vortex.theme";
const ACCENT_STORAGE_KEY = "vortex.accent";

interface BaseToken {
  primary: string;
  foreground: string;
  ring: string;
}

export interface ColorToken extends BaseToken {
  background: string;
  card: string;
  secondary: string;
  muted: string;
  accent: string;
  border: string;
  input: string;
}

const PALETTES: Record<string, { light: BaseToken; dark: BaseToken }> = {
  vortex: {
    light: { primary: "152 76% 36%", foreground: "0 0% 100%", ring: "152 76% 36%" },
    dark: { primary: "145 63% 49%", foreground: "150 42% 7%", ring: "145 63% 49%" },
  },
  blue: {
    light: { primary: "215 80% 50%", foreground: "0 0% 100%", ring: "215 80% 50%" },
    dark: { primary: "215 85% 58%", foreground: "215 90% 10%", ring: "215 85% 58%" },
  },
  teal: {
    light: { primary: "175 75% 36%", foreground: "0 0% 100%", ring: "175 75% 36%" },
    dark: { primary: "175 70% 45%", foreground: "175 80% 8%", ring: "175 70% 45%" },
  },
  green: {
    light: { primary: "145 65% 38%", foreground: "0 0% 100%", ring: "145 65% 38%" },
    dark: { primary: "145 63% 49%", foreground: "150 42% 7%", ring: "145 63% 49%" },
  },
  yellow: {
    light: { primary: "42 95% 42%", foreground: "0 0% 100%", ring: "42 95% 42%" },
    dark: { primary: "42 95% 50%", foreground: "42 90% 10%", ring: "42 95% 50%" },
  },
  orange: {
    light: { primary: "24 95% 48%", foreground: "0 0% 100%", ring: "24 95% 48%" },
    dark: { primary: "24 95% 55%", foreground: "24 90% 10%", ring: "24 95% 55%" },
  },
  red: {
    light: { primary: "355 75% 48%", foreground: "0 0% 100%", ring: "355 75% 48%" },
    dark: { primary: "355 80% 58%", foreground: "355 90% 10%", ring: "355 80% 58%" },
  },
  pink: {
    light: { primary: "320 65% 50%", foreground: "0 0% 100%", ring: "320 65% 50%" },
    dark: { primary: "320 70% 60%", foreground: "320 90% 10%", ring: "320 70% 60%" },
  },
  purple: {
    light: { primary: "280 60% 50%", foreground: "0 0% 100%", ring: "280 60% 50%" },
    dark: { primary: "280 65% 62%", foreground: "280 90% 10%", ring: "280 65% 62%" },
  },
  slate: {
    light: { primary: "220 15% 45%", foreground: "0 0% 100%", ring: "220 15% 45%" },
    dark: { primary: "220 18% 60%", foreground: "220 20% 10%", ring: "220 18% 60%" },
  },
};

function rgbToHsl(r: number, g: number, b: number): { h: number; s: number; l: number } {
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  let h = 0;
  let s = 0;
  const l = (max + min) / 2;

  if (max !== min) {
    const d = max - min;
    s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
    switch (max) {
      case r:
        h = (g - b) / d + (g < b ? 6 : 0);
        break;
      case g:
        h = (b - r) / d + 2;
        break;
      case b:
        h = (r - g) / d + 4;
        break;
    }
    h /= 6;
  }
  return {
    h: Math.round(h * 360),
    s: Math.round(s * 100),
    l: Math.round(l * 100),
  };
}

function parseHexOrRgb(input: string): { h: number; s: number; l: number } | null {
  const trimmed = input.trim();
  if (trimmed.startsWith("#") || /^[0-9a-fA-F]{6}$/.test(trimmed)) {
    const hex = trimmed.replace("#", "");
    if (hex.length === 6) {
      const r = parseInt(hex.substring(0, 2), 16) / 255;
      const g = parseInt(hex.substring(2, 4), 16) / 255;
      const b = parseInt(hex.substring(4, 6), 16) / 255;
      return rgbToHsl(r, g, b);
    }
  }
  const parts = trimmed.split(",").map((p) => parseInt(p.trim(), 10));
  if (parts.length === 3 && parts.every((n) => !isNaN(n) && n >= 0 && n <= 255)) {
    return rgbToHsl(parts[0] / 255, parts[1] / 255, parts[2] / 255);
  }
  return null;
}

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

function detectAccentPreference(): AccentColor {
  const stored =
    typeof localStorage !== "undefined"
      ? (localStorage.getItem(ACCENT_STORAGE_KEY) as AccentColor | null)
      : null;
  if (stored && (stored === "system" || stored in PALETTES)) {
    return stored;
  }
  return "system";
}

export const themePreference = ref<ThemePreference>(detectPreference());
export const accentPreference = ref<AccentColor>(detectAccentPreference());
export const detectedSystemAccent = ref<string | null>(null);

export async function refreshSystemAccent(): Promise<void> {
  try {
    const detected = await getSystemAccentColor();
    if (detected) {
      detectedSystemAccent.value = detected.toLowerCase();
    }
  } catch {
  }
}

if (typeof window !== "undefined") {
  void refreshSystemAccent();
  window.addEventListener("focus", () => {
    if (accentPreference.value === "system") {
      void refreshSystemAccent();
    }
  });
}

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

function computeThemeTokens(
  h: number,
  base: BaseToken,
  isDark: boolean,
  isSlate = false,
): ColorToken {
  const sat = isSlate ? 3 : 7;
  if (isDark) {
    return {
      primary: base.primary,
      foreground: base.foreground,
      ring: base.ring,
      background: `${h} ${sat}% 8%`,
      card: `${h} ${sat}% 11%`,
      secondary: `${h} ${sat}% 14%`,
      muted: `${h} ${sat}% 14%`,
      accent: `${h} ${sat}% 17%`,
      border: `${h} ${Math.max(0, sat - 2)}% 20%`,
      input: `${h} ${Math.max(0, sat - 2)}% 18%`,
    };
  } else {
    return {
      primary: base.primary,
      foreground: base.foreground,
      ring: base.ring,
      background: `${h} ${sat + 2}% 98%`,
      card: "0 0% 100%",
      secondary: `${h} ${sat}% 96%`,
      muted: `${h} ${sat}% 96%`,
      accent: `${h} ${sat + 2}% 94%`,
      border: `${h} ${sat}% 88%`,
      input: `${h} ${sat}% 90%`,
    };
  }
}

function resolveAccentTokens(themeMode: Theme): ColorToken {
  const isDark = themeMode === "dark";
  let target = accentPreference.value;
  if (target === "system") {
    const sys = detectedSystemAccent.value;
    if (sys && sys in PALETTES) {
      target = sys as AccentColor;
    } else if (sys) {
      const parsed = parseHexOrRgb(sys);
      if (parsed) {
        if (!isDark) {
          const l = Math.min(parsed.l, 48);
          const base: BaseToken = {
            primary: `${parsed.h} ${parsed.s}% ${l}%`,
            foreground: "0 0% 100%",
            ring: `${parsed.h} ${parsed.s}% ${l}%`,
          };
          return computeThemeTokens(parsed.h, base, false);
        } else {
          const l = Math.max(parsed.l, 55);
          const base: BaseToken = {
            primary: `${parsed.h} ${parsed.s}% ${l}%`,
            foreground: `${parsed.h} ${parsed.s}% 10%`,
            ring: `${parsed.h} ${parsed.s}% ${l}%`,
          };
          return computeThemeTokens(parsed.h, base, true);
        }
      }
      target = "blue";
    } else {
      target = "blue";
    }
  }

  const palette = PALETTES[target] ?? PALETTES.vortex;
  const base = palette[themeMode];
  const h = parseInt(base.primary.split(" ")[0], 10) || 150;
  return computeThemeTokens(h, base, isDark, target === "slate");
}

watchEffect(() => {
  theme.value = resolveTheme(themePreference.value);
  const currentTheme = theme.value;
  if (typeof document !== "undefined") {
    const root = document.documentElement;
    if (currentTheme === "dark") root.classList.add("dark");
    else root.classList.remove("dark");

    const tokens = resolveAccentTokens(currentTheme);
    root.style.setProperty("--primary", tokens.primary);
    root.style.setProperty("--primary-foreground", tokens.foreground);
    root.style.setProperty("--ring", tokens.ring);
    root.style.setProperty("--background", tokens.background);
    root.style.setProperty("--card", tokens.card);
    root.style.setProperty("--secondary", tokens.secondary);
    root.style.setProperty("--muted", tokens.muted);
    root.style.setProperty("--accent", tokens.accent);
    root.style.setProperty("--border", tokens.border);
    root.style.setProperty("--input", tokens.input);
  }
  if (typeof localStorage !== "undefined") {
    localStorage.setItem(STORAGE_KEY, themePreference.value);
    localStorage.setItem(ACCENT_STORAGE_KEY, accentPreference.value);
  }
});

export function setThemePreference(pref: ThemePreference) {
  themePreference.value = pref;
}

export function setAccentPreference(accent: AccentColor) {
  accentPreference.value = accent;
}

export function toggleTheme() {
  const current = theme.value;
  setThemePreference(current === "dark" ? "light" : "dark");
}
