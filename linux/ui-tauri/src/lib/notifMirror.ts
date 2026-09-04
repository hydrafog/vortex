import { ref } from "vue";
import {
  setNotifMirrorShow,
  getNotifMirrorShow,
  setNotifMirrorSend,
  getNotifMirrorSend,
} from "./bridge";

const STORAGE_KEY = "vortex.notif_mirror_show";

export const notifMirrorShow = ref<boolean>(
  localStorage.getItem(STORAGE_KEY) !== "false",
);

export function setNotifMirror(show: boolean): void {
  notifMirrorShow.value = show;
  localStorage.setItem(STORAGE_KEY, String(show));
  void setNotifMirrorShow(show);
}

const SEND_KEY = "vortex.notif_mirror_send";

export const notifMirrorSend = ref<boolean>(
  localStorage.getItem(SEND_KEY) !== "false",
);

export function setNotifSend(send: boolean): void {
  notifMirrorSend.value = send;
  localStorage.setItem(SEND_KEY, String(send));
  void setNotifMirrorSend(send);
}

export function initNotifMirror(): void {
  getNotifMirrorShow()
    .then((v) => {
      if (notifMirrorShow.value !== v) void setNotifMirrorShow(notifMirrorShow.value);
    })
    .catch(() => {
      if (!notifMirrorShow.value) void setNotifMirrorShow(false);
    });
  getNotifMirrorSend()
    .then((v) => {
      if (notifMirrorSend.value !== v) void setNotifMirrorSend(notifMirrorSend.value);
    })
    .catch(() => {
      if (!notifMirrorSend.value) void setNotifMirrorSend(false);
    });
}
