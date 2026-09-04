import { ref } from "vue";
import { getProximitySettings, setProximitySettings } from "./bridge";

export const proximityAutoLock = ref<boolean>(false);
export const proximityAutoUnlock = ref<boolean>(false);

function push(): void {
  void setProximitySettings({
    auto_lock: proximityAutoLock.value,
    auto_unlock: proximityAutoUnlock.value,
  });
}

export function setProximityAutoLock(v: boolean): void {
  proximityAutoLock.value = v;
  push();
}

export function setProximityAutoUnlock(v: boolean): void {
  proximityAutoUnlock.value = v;
  push();
}

export async function initProximity(): Promise<void> {
  try {
    const s = await getProximitySettings();
    proximityAutoLock.value = s.auto_lock;
    proximityAutoUnlock.value = s.auto_unlock;
  } catch {
  }
}
