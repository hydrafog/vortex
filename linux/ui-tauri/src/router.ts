import { createRouter, createWebHashHistory } from "vue-router";

export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: "/", name: "home", component: () => import("@/pages/home/HomePage.vue") },
    { path: "/settings", name: "settings", component: () => import("@/pages/settings/SettingsPage.vue") },
    { path: "/clipboard", name: "clipboard", component: () => import("@/pages/clipboard/ClipboardPage.vue") },
  ],
});
