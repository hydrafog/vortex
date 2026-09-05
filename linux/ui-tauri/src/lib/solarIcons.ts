import { defineComponent, h } from "vue";

// NOTE: Vendored locally to keep web bundle lean and avoid an extra network dependency.

export const SOLAR_VARIANT = "Linear";
export const SOLAR_STROKE = 1.7;
export const SOLAR_STROKE_MIN = 1.5;
export const SOLAR_STROKE_MAX = 1.8;
export const SOLAR_SIZE = 24;
export const SOLAR_VIEWBOX = "0 0 24 24";

export class IconLoadFailure extends Error {
  readonly iconName: string;
  readonly causeDetail: unknown;
  constructor(iconName: string, causeDetail?: unknown) {
    super(`Solar icon failed to resolve: ${iconName}`);
    this.name = "IconLoadFailure";
    this.iconName = iconName;
    this.causeDetail = causeDetail;
  }
}

export const SOLAR_ICON_ALLOWLIST: ReadonlySet<string> = new Set([
  "SolarDevices",
  "SolarUsersGroup",
  "SolarPhone",
  "SolarChatSquare",
  "SolarNotebook",
  "SolarSettings",
  "SolarChevronsLeft",
  "SolarMoon",
  "SolarSun",
  "SolarGlobe",
  "SolarArrowLeft",
  "SolarHeadphones",
  "SolarBell",
  "SolarBellBing",
  "SolarLock",
  "SolarUnlock",
  "SolarClipboardList",
  "SolarCursor",
  "SolarFileDownload",
  "SolarLaptop",
  "SolarSmartphone",
  "SolarVideocamera",
  "SolarMonitorShare",
  "SolarLoader",
  "SolarAdd",
  "SolarBattery",
  "SolarBatteryLow",
  "SolarBatteryHalf",
  "SolarBatteryFull",
  "SolarBatteryCharge",
  "SolarPin",
  "SolarPinSlash",
  "SolarTrash",
  "SolarSearch",
  "SolarGallery",
  "SolarText",
  "SolarClock",
  "SolarHardDrive",
  "SolarArrowDown",
  "SolarSend",
  "SolarDangerCircle",
  "SolarRestart",
  "SolarSmile",
  "SolarArrowDownLeft",
  "SolarArrowUpRight",
  "SolarArrowRightLeft",
  "SolarArrowLeftRight",
  "SolarAltArrowLeft",
  "SolarAltArrowRight",
  "SolarBluetooth",
  "SolarCheck",
  "SolarClose",
  "SolarTranslation",
  "SolarShieldCheck",
  "SolarDangerTriangle",
  "SolarRefresh",
]);

export function assertSolarIconName(name: string): void {
  if (!SOLAR_ICON_ALLOWLIST.has(name)) {
    throw new IconLoadFailure(name, "not in allowlist");
  }
}

function createSolarIcon(displayName: string, inner: string) {
  assertSolarIconName(displayName);
  return defineComponent({
    name: displayName,
    props: {
      size: { type: [Number, String], default: SOLAR_SIZE },
      strokeWidth: { type: [Number, String], default: SOLAR_STROKE },
    },
    setup(props, { attrs }) {
      return () =>
        h("svg", {
          xmlns: "http://www.w3.org/2000/svg",
          viewBox: SOLAR_VIEWBOX,
          width: props.size,
          height: props.size,
          fill: "none",
          stroke: "currentColor",
          "stroke-width": props.strokeWidth,
          "stroke-linecap": "round",
          "stroke-linejoin": "round",
          "data-solar": "Linear",
          "data-icon": displayName,
          ...attrs,
          innerHTML: inner,
        });
    },
  });
}


export const SolarDevices = createSolarIcon(
  "SolarDevices",
  '<rect x="2" y="4" width="12" height="10" rx="2"/><rect x="16" y="8" width="6" height="12" rx="1.5"/><path d="M6 18h6"/>'
);
export const SolarUsersGroup = createSolarIcon(
  "SolarUsersGroup",
  '<circle cx="9" cy="8" r="3.2"/><path d="M3.5 19c.6-3 2.8-4.5 5.5-4.5s4.9 1.5 5.5 4.5"/><circle cx="17" cy="9" r="2.4"/><path d="M16 14.6c2.3.2 3.9 1.6 4.4 4"/>'
);
export const SolarPhone = createSolarIcon(
  "SolarPhone",
  '<path d="M5 4h4l1.5 4.5L8 10.5a12 12 0 0 0 5.5 5.5l2-2.5L20 15v4a1.5 1.5 0 0 1-1.7 1.5C10.5 20 4 13.5 3.5 5.7A1.5 1.5 0 0 1 5 4Z"/>'
);
export const SolarChatSquare = createSolarIcon(
  "SolarChatSquare",
  '<path d="M4 5h16v11H9l-5 4V5Z"/><path d="M8 9.5h8M8 12.5h5"/>'
);
export const SolarNotebook = createSolarIcon(
  "SolarNotebook",
  '<path d="M6 3.5h11a1.5 1.5 0 0 1 1.5 1.5v14a1.5 1.5 0 0 1-1.5 1.5H6a1.5 1.5 0 0 1-1.5-1.5V5A1.5 1.5 0 0 1 6 3.5Z"/><path d="M9 8.5h6M9 12h6M9 15.5h4"/>'
);
export const SolarSettings = createSolarIcon(
  "SolarSettings",
  '<circle cx="12" cy="12" r="3"/><path d="M13.7654 2.15224C13.3978 2 12.9319 2 12 2C11.0681 2 10.6022 2 10.2346 2.15224C9.74457 2.35523 9.35522 2.74458 9.15223 3.23463C9.05957 3.45834 9.0233 3.7185 9.00911 4.09799C8.98826 4.65568 8.70226 5.17189 8.21894 5.45093C7.73564 5.72996 7.14559 5.71954 6.65219 5.45876C6.31645 5.2813 6.07301 5.18262 5.83294 5.15102C5.30704 5.08178 4.77518 5.22429 4.35436 5.5472C4.03874 5.78938 3.80577 6.1929 3.33983 6.99993C2.87389 7.80697 2.64092 8.21048 2.58899 8.60491C2.51976 9.1308 2.66227 9.66266 2.98518 10.0835C3.13256 10.2756 3.3397 10.437 3.66119 10.639C4.1338 10.936 4.43789 11.4419 4.43786 12C4.43783 12.5581 4.13375 13.0639 3.66118 13.3608C3.33965 13.5629 3.13248 13.7244 2.98508 13.9165C2.66217 14.3373 2.51966 14.8691 2.5889 15.395C2.64082 15.7894 2.87379 16.193 3.33973 17C3.80568 17.807 4.03865 18.2106 4.35426 18.4527C4.77508 18.7756 5.30694 18.9181 5.83284 18.8489C6.07289 18.8173 6.31632 18.7186 6.65204 18.5412C7.14547 18.2804 7.73556 18.27 8.2189 18.549C8.70224 18.8281 8.98826 19.3443 9.00911 19.9021C9.02331 20.2815 9.05957 20.5417 9.15223 20.7654C9.35522 21.2554 9.74457 21.6448 10.2346 21.8478C10.6022 22 11.0681 22 12 22C12.9319 22 13.3978 22 13.7654 21.8478C14.2554 21.6448 14.6448 21.2554 14.8477 20.7654C14.9404 20.5417 14.9767 20.2815 14.9909 19.902C15.0117 19.3443 15.2977 18.8281 15.781 18.549C16.2643 18.2699 16.8544 18.2804 17.3479 18.5412C17.6836 18.7186 17.927 18.8172 18.167 18.8488C18.6929 18.9181 19.2248 18.7756 19.6456 18.4527C19.9612 18.2105 20.1942 17.807 20.6601 16.9999C21.1261 16.1929 21.3591 15.7894 21.411 15.395C21.4802 14.8691 21.3377 14.3372 21.0148 13.9164C20.8674 13.7243 20.6602 13.5628 20.3387 13.3608C19.8662 13.0639 19.5621 12.558 19.5621 11.9999C19.5621 11.4418 19.8662 10.9361 20.3387 10.6392C20.6603 10.4371 20.8675 10.2757 21.0149 10.0835C21.3378 9.66273 21.4803 9.13087 21.4111 8.60497C21.3592 8.21055 21.1262 7.80703 20.6602 7C20.1943 6.19297 19.9613 5.78945 19.6457 5.54727C19.2249 5.22436 18.693 5.08185 18.1671 5.15109C17.9271 5.18269 17.6837 5.28136 17.3479 5.4588C16.8545 5.71959 16.2644 5.73002 15.7811 5.45096C15.2977 5.17191 15.0117 4.65566 14.9909 4.09794C14.9767 3.71848 14.9404 3.45833 14.8477 3.23463C14.6448 2.74458 14.2554 2.35523 13.7654 2.15224Z"/>'
);
export const SolarChevronsLeft = createSolarIcon(
  "SolarChevronsLeft",
  '<path d="M13 6l-6 6 6 6M19 6l-6 6 6 6"/>'
);
export const SolarMoon = createSolarIcon(
  "SolarMoon",
  '<path d="M20 14.5A8.5 8.5 0 0 1 9.5 4 8.5 8.5 0 1 0 20 14.5Z"/>'
);
export const SolarSun = createSolarIcon(
  "SolarSun",
  '<circle cx="12" cy="12" r="4"/><path d="M12 2.5v2.5M12 19v2.5M2.5 12H5M19 12h2.5M5 5l1.8 1.8M17.2 17.2 19 19M19 5l-1.8 1.8M6.8 17.2 5 19"/>'
);
export const SolarGlobe = createSolarIcon(
  "SolarGlobe",
  '<circle cx="12" cy="12" r="8.5"/><path d="M3.5 12h17M12 3.5c2.5 2.3 3.8 5.2 3.8 8.5s-1.3 6.2-3.8 8.5c-2.5-2.3-3.8-5.2-3.8-8.5S9.5 5.8 12 3.5Z"/>'
);
export const SolarArrowLeft = createSolarIcon(
  "SolarArrowLeft",
  '<path d="M19 12H5M11 6l-6 6 6 6"/>'
);
export const SolarHeadphones = createSolarIcon(
  "SolarHeadphones",
  '<path d="M4 15v-2a8 8 0 0 1 16 0v2"/><rect x="3" y="14" width="4" height="6" rx="1.5"/><rect x="17" y="14" width="4" height="6" rx="1.5"/>'
);
export const SolarBell = createSolarIcon(
  "SolarBell",
  '<path d="M6 10a6 6 0 0 1 12 0c0 4 1.5 5.5 1.5 5.5h-15S6 14 6 10Z"/><path d="M10 19a2.2 2.2 0 0 0 4 0"/>'
);
export const SolarBellBing = createSolarIcon(
  "SolarBellBing",
  '<path d="M6 10a6 6 0 0 1 12 0c0 4 1.5 5.5 1.5 5.5h-15S6 14 6 10Z"/><path d="M10 19a2.2 2.2 0 0 0 4 0"/><path d="M19 4l.8 1.6L21.5 6l-1.7.8L19 8.5l-.8-1.7L16.5 6l1.7-.4L19 4Z"/>'
);
export const SolarLock = createSolarIcon(
  "SolarLock",
  '<rect x="5" y="10.5" width="14" height="9.5" rx="2"/><path d="M8 10.5V8a4 4 0 0 1 8 0v2.5"/>'
);
export const SolarUnlock = createSolarIcon(
  "SolarUnlock",
  '<rect x="5" y="10.5" width="14" height="9.5" rx="2"/><path d="M8 10.5V8a4 4 0 0 1 7.8-1.2"/>'
);
export const SolarClipboardList = createSolarIcon(
  "SolarClipboardList",
  '<rect x="5" y="5" width="14" height="16" rx="2"/><rect x="9" y="3" width="6" height="4" rx="1"/><path d="M9 11h6M9 14.5h6M9 18h4"/>'
);
export const SolarCursor = createSolarIcon(
  "SolarCursor",
  '<path d="M6 3.5 18 12l-6.5 1L9 19.5 6 3.5Z"/>'
);
export const SolarFileDownload = createSolarIcon(
  "SolarFileDownload",
  '<path d="M6 3.5h8L19 8.5V20a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V4.5a1 1 0 0 1 1-1Z"/><path d="M14 3.5V8.5H19M12 12v6M9.5 15.5 12 18l2.5-2.5"/>'
);
export const SolarLaptop = createSolarIcon(
  "SolarLaptop",
  '<rect x="4" y="4.5" width="16" height="11" rx="1.5"/><path d="M2.5 19.5h19"/>'
);
export const SolarSmartphone = createSolarIcon(
  "SolarSmartphone",
  '<rect x="7" y="2.5" width="10" height="19" rx="2.5"/><path d="M10.5 18.5h3"/>'
);
export const SolarVideocamera = createSolarIcon(
  "SolarVideocamera",
  '<rect x="2.5" y="7" width="13" height="10" rx="2"/><path d="M15.5 10.5 21.5 7v10l-6-3.5"/>'
);
export const SolarMonitorShare = createSolarIcon(
  "SolarMonitorShare",
  '<rect x="3" y="4" width="18" height="12" rx="2"/><path d="M9 20h6M12 16v4M12 7v4M10 9l2-2 2 2"/>'
);
export const SolarLoader = createSolarIcon(
  "SolarLoader",
  '<path d="M12 3.5a8.5 8.5 0 1 0 8.5 8.5"/>'
);
export const SolarAdd = createSolarIcon(
  "SolarAdd",
  '<path d="M12 5v14M5 12h14"/>'
);
export const SolarBattery = createSolarIcon(
  "SolarBattery",
  '<rect x="2.5" y="8" width="17" height="8" rx="2"/><path d="M21.5 11v2"/><path d="M6 11v2M9.5 11v2M13 11v2"/>'
);
export const SolarBatteryLow = createSolarIcon(
  "SolarBatteryLow",
  '<rect x="2.5" y="8" width="17" height="8" rx="2"/><path d="M21.5 11v2"/><path d="M6 11v2"/>'
);
export const SolarBatteryHalf = createSolarIcon(
  "SolarBatteryHalf",
  '<rect x="2.5" y="8" width="17" height="8" rx="2"/><path d="M21.5 11v2"/><path d="M6 11v2M9.5 11v2"/>'
);
export const SolarBatteryFull = createSolarIcon(
  "SolarBatteryFull",
  '<rect x="2.5" y="8" width="17" height="8" rx="2"/><path d="M21.5 11v2"/><path d="M6 11v2M9.5 11v2M13 11v2M16 11v2"/>'
);
export const SolarBatteryCharge = createSolarIcon(
  "SolarBatteryCharge",
  '<rect x="2.5" y="8" width="17" height="8" rx="2"/><path d="M21.5 11v2"/><path d="M11 9.5 9.5 12.5H12l-1 3 3.5-4.5H11l1-1.5Z"/>'
);
export const SolarPin = createSolarIcon(
  "SolarPin",
  '<path d="M9 4h6l1 7 2.5 3v1.5h-13V14L8 11l1-7Z"/><path d="M12 15.5V21"/>'
);
export const SolarPinSlash = createSolarIcon(
  "SolarPinSlash",
  '<path d="M9 4h6l1 7 2.5 3v1.5h-13V14L8 11l1-7Z"/><path d="M12 15.5V21M4 4l16 16"/>'
);
export const SolarTrash = createSolarIcon(
  "SolarTrash",
  '<path d="M4 7h16M9.5 7V4.5h5V7M6.5 7l1 13h9l1-13"/><path d="M10 11v6M14 11v6"/>'
);
export const SolarSearch = createSolarIcon(
  "SolarSearch",
  '<circle cx="11" cy="11" r="6.5"/><path d="m16 16 5 5"/>'
);
export const SolarGallery = createSolarIcon(
  "SolarGallery",
  '<rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="9" cy="10" r="1.6"/><path d="m5 18 5-5 3 3 2.5-2.5L20 18"/>'
);
export const SolarText = createSolarIcon(
  "SolarText",
  '<path d="M5 6V4.5h14V6M12 4.5V20M9 20h6"/>'
);
export const SolarClock = createSolarIcon(
  "SolarClock",
  '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>'
);
export const SolarHardDrive = createSolarIcon(
  "SolarHardDrive",
  '<rect x="3" y="6" width="18" height="12" rx="2"/><path d="M7 14.5h.01M11 14.5h.01"/><path d="M17 9.5h.01"/>'
);
export const SolarArrowDown = createSolarIcon(
  "SolarArrowDown",
  '<path d="M12 5v14M6 13l6 6 6-6"/>'
);
export const SolarSend = createSolarIcon(
  "SolarSend",
  '<path d="M20 4 10.5 13.5M20 4l-6.5 16-3-6.5L4 10.5 20 4Z"/>'
);
export const SolarDangerCircle = createSolarIcon(
  "SolarDangerCircle",
  '<circle cx="12" cy="12" r="8.5"/><path d="M12 8v5M12 16h.01"/>'
);
export const SolarRestart = createSolarIcon(
  "SolarRestart",
  '<path d="M20 12a8 8 0 1 1-2.3-5.6M20 3.5V8h-4.5"/>'
);
export const SolarSmile = createSolarIcon(
  "SolarSmile",
  '<circle cx="12" cy="12" r="8.5"/><path d="M8.5 14.5c1 1.2 2.2 1.8 3.5 1.8s2.5-.6 3.5-1.8"/><path d="M9 9.5h.01M15 9.5h.01"/>'
);
export const SolarArrowDownLeft = createSolarIcon(
  "SolarArrowDownLeft",
  '<path d="M17 7 7 17M16 17H7V8"/>'
);
export const SolarArrowUpRight = createSolarIcon(
  "SolarArrowUpRight",
  '<path d="M7 17 17 7M8 7h9v9"/>'
);
export const SolarArrowRightLeft = createSolarIcon(
  "SolarArrowRightLeft",
  '<path d="M4 8h13l-3-3M20 16H7l3 3"/>'
);
export const SolarArrowLeftRight = createSolarIcon(
  "SolarArrowLeftRight",
  '<path d="M20 8H7l3-3M4 16h13l-3 3"/>'
);
export const SolarAltArrowLeft = createSolarIcon(
  "SolarAltArrowLeft",
  '<path d="M14.5 6 8.5 12l6 6"/>'
);
export const SolarAltArrowRight = createSolarIcon(
  "SolarAltArrowRight",
  '<path d="m9.5 6 6 6-6 6"/>'
);
export const SolarBluetooth = createSolarIcon(
  "SolarBluetooth",
  '<path d="M7 8l10 8-5 4V4l5 4L7 16"/><path d="M7 8v8"/>'
);
export const SolarCheck = createSolarIcon(
  "SolarCheck",
  '<path d="m5 12.5 4.5 4.5L19 7.5"/>'
);
export const SolarClose = createSolarIcon(
  "SolarClose",
  '<path d="M6 6l12 12M18 6 6 18"/>'
);
export const SolarTranslation = createSolarIcon(
  "SolarTranslation",
  '<path d="M4 5h9M8.5 3.5v1.5c0 3.5-2 6.5-4.5 8M6 8.5c1 2.5 3 4.5 5.5 5.5"/><path d="m13 21 4-9 4 9M14.5 17.5h5"/>'
);
export const SolarShieldCheck = createSolarIcon(
  "SolarShieldCheck",
  '<path d="M12 3 5 5.8v5.4c0 4.4 2.9 7.6 7 9.3 4.1-1.7 7-4.9 7-9.3V5.8L12 3Z"/><path d="m9 11.5 2.2 2.2L15.5 9.5"/>'
);
export const SolarDangerTriangle = createSolarIcon(
  "SolarDangerTriangle",
  '<path d="M12 4 3.5 20h17L12 4Z"/><path d="M12 10v4M12 17h.01"/>'
);
export const SolarRefresh = createSolarIcon(
  "SolarRefresh",
  '<path d="M20 12a8 8 0 0 1-14.2 5M4 12a8 8 0 0 1 14.2-5"/><path d="M18.5 3.5V8H14M5.5 20.5V16H10"/>'
);

export function resolveSolarIcon(name: string) {
  const map: Record<string, unknown> = {
    SolarDevices,
    SolarUsersGroup,
    SolarPhone,
    SolarChatSquare,
    SolarNotebook,
    SolarSettings,
    SolarChevronsLeft,
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
    SolarLaptop,
    SolarSmartphone,
    SolarVideocamera,
    SolarMonitorShare,
    SolarLoader,
    SolarAdd,
    SolarBattery,
    SolarBatteryLow,
    SolarBatteryHalf,
    SolarBatteryFull,
    SolarBatteryCharge,
    SolarPin,
    SolarPinSlash,
    SolarTrash,
    SolarSearch,
    SolarGallery,
    SolarText,
    SolarClock,
    SolarHardDrive,
    SolarArrowDown,
    SolarSend,
    SolarDangerCircle,
    SolarRestart,
    SolarSmile,
    SolarArrowDownLeft,
    SolarArrowUpRight,
    SolarArrowRightLeft,
    SolarArrowLeftRight,
    SolarAltArrowLeft,
    SolarAltArrowRight,
    SolarBluetooth,
    SolarCheck,
    SolarClose,
    SolarTranslation,
    SolarShieldCheck,
    SolarDangerTriangle,
    SolarRefresh,
  };
  try {
    assertSolarIconName(name);
    const found = map[name];
    if (!found) {
      throw new IconLoadFailure(name, "missing component");
    }
    return found;
  } catch (e) {
    if (e instanceof IconLoadFailure) {
      throw e;
    }
    throw new IconLoadFailure(name, e);
  }
}
