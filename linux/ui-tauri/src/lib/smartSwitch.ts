import { ref } from "vue";
import { listen } from "@tauri-apps/api/event";
import { setSmartSwitchEnabled, getSmartSwitchEnabled } from "./bridge";

export const smartSwitchEnabled = ref<boolean>(true);

export function setSmartSwitch(v: boolean): void {
  smartSwitchEnabled.value = v;
  void setSmartSwitchEnabled(v);
}

export async function initSmartSwitch(): Promise<void> {
  try {
    smartSwitchEnabled.value = await getSmartSwitchEnabled();
  } catch {
  }
  await listen<boolean>("vortex:smart_switch", (ev) => {
    smartSwitchEnabled.value = !!ev.payload;
  });
}
