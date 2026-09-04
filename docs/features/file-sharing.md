# Instant File Sharing

Vortex enables rapid local file drops between Linux and Android without intermediate cloud uploads.

## Key Capabilities

- **Wi-Fi Direct Fast Path**: Automatically selects Wi-Fi Direct or local subnet streaming to bypass BLE speed bottlenecks.
- **Folder Packaging**: Desktop client uses an in-process zip implementation (`zip` crate) to stream entire directory structures as single payloads.
- **File Manager Integration**: Provides context menu entries for GNOME Nautilus (`nautilus-python`), KDE Dolphin (`service-menus`), and generic file pickers.
- **Auto-Accept Option**: When configured in settings, trusted paired devices can receive files directly to the designated incoming directory without manual prompts.
