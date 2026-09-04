import { ref } from "vue";

const STORAGE_KEY = "vortex.intro_done";

export const introDone = ref(localStorage.getItem(STORAGE_KEY) === "true");

export function markIntroDone(): void {
  introDone.value = true;
  localStorage.setItem(STORAGE_KEY, "true");
}
