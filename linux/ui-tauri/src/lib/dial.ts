import { invoke } from "@tauri-apps/api/core";

export async function dial(number: string): Promise<void> {
  const n = (number ?? "").trim();
  if (!n) return;
  try {
    await invoke("dial", { number: n });
  } catch (e) {
    console.warn("dial failed", e);
  }
}
