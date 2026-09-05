# Instant File Sharing

Vortex enables rapid local file drops between Linux and Android without intermediate cloud uploads.

## Key Capabilities

- **AirDrop-Style Streaming Pipeline**: Direct disk-to-wire and wire-to-disk chunked pipeline with no arbitrary file size limits. Files of any size transfer in constant O(1) RAM (~60 KB buffer).
- **Zero-Heap Receiver**: The Android receiver streams incoming 60 KB encrypted frames directly into `MediaStore.Downloads` output streams, eliminating heap buffering and memory bottlenecks.
- **Wi-Fi Direct Fast Path**: Automatically selects Wi-Fi Direct or local subnet streaming to bypass BLE speed bottlenecks.
- **Folder Packaging**: Desktop client packages folder trees into compressed zip streams written directly to storage, transferring whole directories as single units.
- **File Manager and Desktop UI Integration**: Send files via Ricelin desktop pill (drag and drop any files or directories onto the resting pill), GNOME Files ("Share via Vortex"), Dolphin, Yazi terminal file manager (press Shift+O on any selection), the desktop app "Send files" chip on the device card, or the optional System Tray menu.
- **Auto-Accept Option**: When toggled in settings, incoming files from paired devices are automatically accepted without prompts. Persistent settings override host Nix store immutability by atomically replacing symlinks with user configuration files.
