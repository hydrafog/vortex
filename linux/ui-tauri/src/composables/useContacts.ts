import { ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

export interface Contact {
  id: string;
  name: string;
  numbers: string[];
}

export const contacts = ref<Contact[]>([]);
export const contactsLoaded = ref(false);

let started = false;

export async function initContacts(): Promise<void> {
  if (started) return;
  started = true;

  try {
    contacts.value = await invoke<Contact[]>("get_contacts");
    contactsLoaded.value = true;
  } catch {
  }

  await listen<Contact[]>("vortex:contacts", (e) => {
    contacts.value = e.payload ?? [];
    contactsLoaded.value = true;
  });
}
