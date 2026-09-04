import { computed, ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

export interface Note {
  id: string;
  kind: "note" | "todo";
  title: string;
  body: string;
  done: boolean;
  due_at: number;
  updated_at: number;
  deleted: boolean;
}

export type NotesFilter = "all" | "note" | "todo";

export const notes = ref<Note[]>([]);
export const notesLoaded = ref(false);
export const filter = ref<NotesFilter>("all");
export const selectedId = ref<string | null>(null);

let started = false;

export async function initNotes(): Promise<void> {
  if (!started) {
    started = true;
    await listen<Note[]>("vortex:notes", e => {
      notes.value = e.payload ?? [];
    });
  }
  notes.value = await invoke<Note[]>("get_notes");
  notesLoaded.value = true;
}

export const visibleNotes = computed(() => {
  const f = filter.value;
  return notes.value
    .filter(n => f === "all" || n.kind === f)
    .slice()
    .sort((a, b) => b.updated_at - a.updated_at);
});

export const selected = computed(() => notes.value.find(n => n.id === selectedId.value) ?? null);

function uuid(): string {
  return crypto.randomUUID();
}

export async function createNote(kind: "note" | "todo"): Promise<void> {
  const item: Note = {
    id: uuid(),
    kind,
    title: "",
    body: "",
    done: false,
    due_at: 0,
    updated_at: Date.now(),
    deleted: false,
  };
  selectedId.value = item.id;
  await invoke("upsert_note", { item });
}

export async function saveNote(item: Note): Promise<void> {
  await invoke("upsert_note", { item });
}

export async function addTodo(text: string): Promise<void> {
  const t = text.trim();
  if (!t) return;
  const item: Note = {
    id: uuid(),
    kind: "todo",
    title: t,
    body: "",
    done: false,
    due_at: 0,
    updated_at: Date.now(),
    deleted: false,
  };
  await invoke("upsert_note", { item });
}

export async function toggleTodo(id: string, done: boolean): Promise<void> {
  await invoke("toggle_todo", { id, done });
}

export async function deleteNote(id: string): Promise<void> {
  if (selectedId.value === id) selectedId.value = null;
  await invoke("delete_note", { id });
}
