# Instant file sharing

Drop files between Linux and Android over the local network. Nothing uploads to the cloud.

## Transfers

- Files of any size stream in 60 KB chunks from disk to network and back. Memory use stays near 60 KB.
- Android writes each decrypted 60 KB frame straight to `MediaStore.Downloads`. It does not buffer the whole file in memory.
- Vortex picks Wi-Fi Direct or local subnet for the bytes. BLE stays for control because it is too slow for files.
- Folders go as a zip with `STORED` (no recompression) and 1 MB buffered I/O. The receiver unpacks the tree into Downloads.
- Send from the desktop pill by dropping files on it, from GNOME Files with Share via Vortex, from Dolphin, from Yazi with Shift+O, from the Send files chip on the device card, or from the tray menu.
- Android shows progress with file name, position in the queue (`X of Y`), byte counts, and a bar. Updates wait 250 ms between posts so the notification channel stays quiet.
- With auto-accept on in settings, files from a paired device save without a prompt. On NixOS and Home Manager, Vortex replaces the read-only store symlink with a user file first, so the setting sticks.
