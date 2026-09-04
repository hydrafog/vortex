# Troubleshooting Guide

Common issues and remediation steps for Vortex.

## Bluetooth Discovery & Pairing

### Device Not Visible During BLE Scan
1. Ensure Bluetooth is powered on:
   ```bash
   bluetoothctl show
   ```
2. Verify BlueZ daemon is running:
   ```bash
   systemctl status bluetooth
   ```
3. Ensure location/nearby permissions are granted on the Android app (required by Android OS for BLE scanning).

---

## Universal Control & Input

### Cursor Doesn't Cross Screen Edge
1. Confirm the phone is authorized via ADB:
   ```bash
   adb devices
   ```
   Device status must show `device`, not `unauthorized`.
2. Confirm `vortex_inject` is accessible:
   ```bash
   adb shell ls -l /data/local/tmp/vortex_inject
   ```
3. Check Wayland InputCapture portal:
   - On Hyprland / Sway, verify `xdg-desktop-portal-hyprland` or `xdg-desktop-portal-wlr` is active.

---

## Wayland vs X11 Display

- On native Wayland, ensure `GDK_BACKEND=wayland` is set if running outside GNOME fractional scaling.
- If window controls misalign under fractional scaling, test launching with `GDK_BACKEND=x11`.

---

## Logs & Diagnostics

- Run daemon with detailed tracing:
  ```bash
  RUST_LOG=debug cargo run --manifest-path linux/daemon/Cargo.toml
  ```
- View desktop app logs:
  ```bash
  tail -f ~/.local/share/vortex/vortex.log
  ```
