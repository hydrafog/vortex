# Instant File Sharing

Vortex enables rapid local file drops between Linux and Android without intermediate cloud uploads.

## Key Capabilities

- **High Capacity Streaming**: Transfers support individual files up to 2 GB and total batch payloads up to 4 GB over local Wi-Fi and Wi-Fi Direct.
- **Wi-Fi Direct Fast Path**: Automatically selects Wi-Fi Direct or local subnet streaming to bypass BLE speed bottlenecks.
- **Folder Packaging**: Desktop client uses an in-process zip implementation (`zip` crate) to stream entire directory structures as single payloads.
- **File Manager and UI Integration**: Send files via GNOME Files ("Share via Vortex"), the desktop app "Send files" chip on the device card, or the System Tray menu.
- **Auto-Accept Option**: When toggled in settings, incoming files from paired devices are automatically accepted without prompts. Persistent settings override host Nix store immutability by atomically replacing symlinks with user configuration files.
