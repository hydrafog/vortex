<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { SolarAdd, SolarCheck, SolarClose, SolarTrash } from "@/lib/solarIcons";
import DateTimePicker from "./DateTimePicker.vue";
import {
  visibleNotes,
  filter,
  selected,
  selectedId,
  initNotes,
  createNote,
  saveNote,
  toggleTodo,
  deleteNote,
  addTodo,
  type Note,
} from "@/composables/useNotes";
import { cn } from "@/lib/utils";

const { t } = useI18n();

const mode = ref<"notes" | "todos">("notes");
function setMode(m: "notes" | "todos") {
  mode.value = m;
  filter.value = m === "notes" ? "note" : "todo";
}

const editing = ref<Note | null>(null);
watch(
  selected,
  (sel) => {
    if (sel && sel.id !== editing.value?.id) editing.value = { ...sel };
    if (!sel) editing.value = null;
  },
  { immediate: true },
);

let saveTimer: ReturnType<typeof setTimeout> | undefined;
function scheduleSave() {
  if (!editing.value) return;
  clearTimeout(saveTimer);
  const snapshot = { ...editing.value };
  saveTimer = setTimeout(() => saveNote(snapshot), 400);
}

function onDuePicked(ms: number) {
  if (!editing.value) return;
  editing.value.due_at = ms;
  scheduleSave();
}
function setTodoDue(n: Note, ms: number) {
  void saveNote({ ...n, due_at: ms });
}

const newTodo = ref("");
async function addNewTodo() {
  const text = newTodo.value.trim();
  if (!text) return;
  newTodo.value = "";
  await addTodo(text);
}

function relTime(ms: number): string {
  if (!ms) return "";
  const d = new Date(ms);
  const time = d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  if (d.toDateString() === new Date().toDateString()) return time;
  return d.toLocaleDateString([], { month: "short", day: "numeric" });
}
function snippet(n: Note): string {
  const s = (n.body || "").replace(/\s+/g, " ").trim();
  return s || t("notes.empty_body");
}

function select(n: Note) {
  selectedId.value = n.id;
}
async function remove(id: string) {
  await deleteNote(id);
}

const doneCount = computed(() => visibleNotes.value.filter((n) => n.done).length);
const totalTodos = computed(() => visibleNotes.value.length);
const pct = computed(() => (totalTodos.value ? doneCount.value / totalTodos.value : 0));
const RING_C = 2 * Math.PI * 52;
const ringOffset = computed(() => RING_C * (1 - pct.value));

onMounted(() => {
  void initNotes();
  filter.value = "note";
});
</script>

<template>
  <div class="h-full flex flex-col min-h-0">
    <header class="flex items-center justify-between px-5 py-4 border-b border-border bg-card/30 shrink-0">
      <h1 class="text-base font-semibold">{{ t("notes.title") }}</h1>
      <span class="inline-flex items-center gap-1.5 text-[11px] font-medium text-muted-foreground">
        <span class="h-1.5 w-1.5 rounded-full bg-primary" />{{ t("notes.synced") }}
      </span>
    </header>

    <div class="flex-1 flex min-h-0">
      <div class="w-80 shrink-0 border-r border-border flex flex-col min-w-0">
        <div class="px-4 pt-4 pb-3">
          <div class="flex items-center justify-between h-8">
            <span class="text-xs font-semibold uppercase tracking-wider text-muted-foreground">{{ mode === 'notes' ? t("notes.notes") : t("notes.todos") }}</span>
            <button
              v-if="mode === 'notes'"
              class="grid h-8 w-8 place-items-center rounded-[9px] bg-muted/50 border border-border text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
              :title="t('notes.new_note')"
              @click="createNote('note')"
            >
              <SolarAdd class="h-[17px] w-[17px]" :stroke-width="2" />
            </button>
          </div>
          <div class="relative mt-3 grid grid-cols-2 rounded-xl bg-muted/40 border border-border p-1">
            <span
              class="pointer-events-none absolute inset-y-1 left-1 w-[calc(50%-4px)] rounded-lg bg-foreground/[0.08] transition-transform duration-300 ease-[cubic-bezier(.22,1,.36,1)]"
              :style="{ transform: mode === 'todos' ? 'translateX(100%)' : 'translateX(0)' }"
            />
            <button
              class="relative z-[1] rounded-lg py-2 text-[13px] font-semibold transition-colors"
              :class="mode === 'notes' ? 'text-foreground' : 'text-muted-foreground hover:text-foreground'"
              @click="setMode('notes')"
            >
              {{ t("notes.notes") }}
            </button>
            <button
              class="relative z-[1] rounded-lg py-2 text-[13px] font-semibold transition-colors"
              :class="mode === 'todos' ? 'text-foreground' : 'text-muted-foreground hover:text-foreground'"
              @click="setMode('todos')"
            >
              {{ t("notes.todos") }}
            </button>
          </div>
        </div>

        <div v-if="mode === 'notes'" class="flex-1 overflow-y-auto px-3 pb-4 flex flex-col gap-0.5">
          <p v-if="!visibleNotes.length" class="px-3 py-8 text-center text-xs text-muted-foreground">
            {{ t("notes.empty") }}
          </p>
          <button
            v-for="n in visibleNotes"
            :key="n.id"
            :class="cn('flex flex-col gap-0.5 rounded-2xl px-3.5 py-3 text-left transition-colors', selectedId === n.id ? 'bg-foreground/[0.06]' : 'hover:bg-foreground/[0.03]')"
            @click="select(n)"
          >
            <div class="flex items-baseline justify-between gap-2">
              <span class="truncate text-sm font-semibold text-foreground">{{ n.title || t("notes.untitled") }}</span>
              <span class="shrink-0 text-[11px] text-muted-foreground">{{ relTime(n.updated_at) }}</span>
            </div>
            <span class="truncate text-[12.5px] text-muted-foreground">{{ snippet(n) }}</span>
          </button>
        </div>

        <div v-else class="flex-1 overflow-y-auto px-2 pb-4 flex flex-col gap-1">
          <p v-if="!visibleNotes.length" class="px-3 py-8 text-center text-xs text-muted-foreground">
            {{ t("notes.empty") }}
          </p>
          <div
            v-for="n in visibleNotes"
            :key="n.id"
            class="group flex items-start gap-3 rounded-xl px-2.5 py-2.5 transition-colors hover:bg-foreground/[0.03]"
          >
            <button
              :class="cn('mt-px grid h-6 w-6 shrink-0 place-items-center rounded-full border-2 transition-all', n.done ? 'border-primary bg-primary text-primary-foreground' : 'border-muted-foreground/40 hover:border-muted-foreground')"
              @click="toggleTodo(n.id, !n.done)"
            >
              <SolarCheck v-if="n.done" class="h-[13px] w-[13px]" :stroke-width="3" />
            </button>
            <div class="min-w-0 flex-1">
              <span :class="cn('text-[14.5px] transition-colors', n.done ? 'text-muted-foreground line-through' : 'text-foreground')">
                {{ n.title || t("notes.untitled") }}
              </span>
            </div>
            <DateTimePicker :model-value="n.due_at" @update:model-value="(ms: number) => setTodoDue(n, ms)" />
            <button
              class="grid h-[26px] w-[26px] shrink-0 place-items-center rounded-lg text-muted-foreground/60 opacity-0 transition-colors hover:bg-foreground/[0.08] hover:text-foreground group-hover:opacity-100"
              :title="t('notes.delete')"
              @click="remove(n.id)"
            >
              <SolarClose class="h-[15px] w-[15px]" :stroke-width="2" />
            </button>
          </div>
          <div class="mt-auto px-3.5 pt-2.5">
            <div class="flex items-center gap-2 rounded-full border border-border bg-muted/40 py-1.5 pl-4 pr-1.5">
              <input
                v-model="newTodo"
                :placeholder="t('notes.add_todo')"
                class="min-w-0 flex-1 bg-transparent text-sm outline-none placeholder:text-muted-foreground"
                @keydown.enter="addNewTodo"
              />
              <button
                class="grid h-9 w-9 shrink-0 place-items-center rounded-full bg-primary text-primary-foreground transition-transform hover:scale-105 disabled:opacity-40 disabled:hover:scale-100"
                :disabled="!newTodo.trim()"
                @click="addNewTodo"
              >
                <SolarAdd class="h-[19px] w-[19px]" :stroke-width="2.4" />
              </button>
            </div>
          </div>
        </div>
      </div>

      <div class="flex-1 flex flex-col min-w-0 min-h-0">
        <template v-if="mode === 'notes'">
          <div v-if="editing" class="flex-1 flex flex-col p-6 min-h-0">
            <div class="flex items-center justify-between gap-4 pb-4 border-b border-border">
              <input
                v-model="editing.title"
                :placeholder="t('notes.untitled')"
                class="min-w-0 flex-1 bg-transparent text-xl font-semibold outline-none placeholder:text-muted-foreground/50"
                @input="scheduleSave"
              />
              <div class="flex items-center gap-2">
                <DateTimePicker :model-value="editing.due_at" @update:model-value="onDuePicked" />
                <button
                  class="grid h-8 w-8 place-items-center rounded-lg text-muted-foreground hover:bg-destructive/10 hover:text-destructive transition-colors"
                  :title="t('notes.delete')"
                  @click="remove(editing.id)"
                >
                  <SolarTrash class="h-4 w-4" />
                </button>
              </div>
            </div>
            <textarea
              v-model="editing.body"
              :placeholder="t('notes.write')"
              class="mt-4 flex-1 resize-none bg-transparent text-sm leading-relaxed outline-none placeholder:text-muted-foreground/40 scrollbar-thin"
              @input="scheduleSave"
            />
          </div>
          <div v-else class="flex-1 flex items-center justify-center text-muted-foreground text-sm">
            {{ t("notes.select_or_create") }}
          </div>
        </template>
        <template v-else>
          <div class="flex-1 flex flex-col items-center justify-center p-8 text-center">
            <div class="relative flex h-32 w-32 items-center justify-center">
              <svg class="h-32 w-32 -rotate-90 transform" viewBox="0 0 120 120">
                <circle
                  cx="60"
                  cy="60"
                  r="50"
                  stroke="currentColor"
                  stroke-width="8"
                  fill="transparent"
                  class="text-muted/40"
                />
                <circle
                  cx="60"
                  cy="60"
                  r="50"
                  stroke="currentColor"
                  stroke-width="8"
                  fill="transparent"
                  class="text-primary ring-fill"
                  stroke-linecap="round"
                  :stroke-dasharray="RING_C.toFixed(1)"
                  :stroke-dashoffset="ringOffset.toFixed(1)"
                />
              </svg>
              <div class="absolute text-2xl font-bold">
                {{ Math.round(pct * 100) }}%
              </div>
            </div>
            <div class="mt-4 text-base font-semibold text-foreground">
              {{ t("notes.todos_done", { done: doneCount, total: totalTodos }) }}
            </div>
            <div class="mt-1 text-xs text-muted-foreground">{{ t("notes.tap_complete") }}</div>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ring-fill {
  transition: stroke-dashoffset 0.6s cubic-bezier(0.22, 1, 0.36, 1);
}
</style>
