import { createRouter, createWebHashHistory } from "vue-router";

export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: "/", name: "home", component: () => import("@/pages/home/HomePage.vue") },
    { path: "/contacts", name: "contacts", component: () => import("@/pages/contacts/ContactsPage.vue") },
    { path: "/recents", name: "recents", component: () => import("@/pages/recents/RecentsPage.vue") },
    { path: "/messages", name: "messages", component: () => import("@/pages/messages/MessagesPage.vue") },
    { path: "/notes", name: "notes", component: () => import("@/pages/notes/NotesPage.vue") },
    {
      path: "/messages/:address",
      name: "messages-thread",
      component: () => import("@/pages/messages/MessagesPage.vue"),
    },
    { path: "/settings", name: "settings", component: () => import("@/pages/settings/SettingsPage.vue") },
    { path: "/clipboard", name: "clipboard", component: () => import("@/pages/clipboard/ClipboardPage.vue") },
  ],
});
