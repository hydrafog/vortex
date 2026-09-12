# Instant file sharing

Drop files between Linux and Android over the local network.

## Transfers

The tray menu Send files entry is the primary desktop file action. It opens the file picker from the resident surface and queues the selection for transfer to the paired phone. The same pipeline serves every other entry point.

- Files of any size stream in 60 KB chunks from disk to network and back. Memory use stays near 60 KB.
- Sockets batch frame writes and flush at batch boundaries or 2 MB thresholds to keep the TCP congestion window saturated.
- Android streams decrypted 60 KB frames through a 1 MB I/O buffer into `MediaStore.Downloads`, eliminating per-chunk FUSE IPC overhead.
- Vortex picks Wi-Fi Direct or local subnet for the bytes; BLE carries control signals.
- Folders go as a zip with `STORED` (no recompression) and 1 MB buffered I/O. The receiver unpacks the tree into Downloads.
- Send from the tray menu, from the desktop pill by dropping files on it, from GNOME Files with Share via Vortex, from Dolphin, from Yazi with Shift+O, or from the Send files chip on the device card.
- Android shows progress with file name, position in the queue (`X of Y`), byte counts, and a bar. Updates wait 250 ms between posts so the notification channel stays quiet.
- With auto-accept on in settings, files from a paired device save directly to the configured incoming directory (`~/.local/share/vortex/incoming_dir`, falling back to `XDG_DOWNLOAD_DIR` or Downloads). On NixOS and Home Manager, Vortex replaces the read-only store symlink with a user file first, so the setting sticks.

## See also

- `docs/architecture/overview.md` describes the tray-first surface.
- `docs/getting-started/installation.md` describes resident tray defaults and autostart.
- `docs/operations/troubleshooting.md` covers tray diagnostics.
- `docs/features/clipboard-sync.md` describes clipboard handoff between devices.
