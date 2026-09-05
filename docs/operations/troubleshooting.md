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

### Pairing Fails or Aborts
1. Transport vs SAS Mismatch:
   - "Pairing failed" indicates a Bluetooth connection timeout, GATT failure, or BlueZ agent conflict.
   - "Pairing canceled for safety" indicates the 3-emoji Short Authentication String (SAS) was explicitly rejected.
2. BlueZ Pairing Agent Conflicts (KDE Plasma / Bluedevil):
   - Vortex registers a Just Works pairing agent with BlueZ. If desktop environment prompts intercept pairing, restart the BlueZ service or disconnect existing third-party connectors (such as KDE Connect).
   - Verify active controller status with `bluetoothctl show`.

---

## Universal Control & Input

### Cursor Doesn't Cross Screen Edge or Screen Mirror Fails
1. Confirm the phone is authorized via ADB:
   ```bash
   adb devices
   ```
   Device status must show `device`, not `unauthorized`.
2. Confirm the Vortex Android APK is installed and running:
   ```bash
   adb shell pm list packages | grep zoir_dev.vortex
   ```
   If uninstalled, reinstall via `install-android.sh` or Android Studio.
3. Confirm `vortex_inject` is accessible:
   ```bash
   adb shell ls -l /data/local/tmp/vortex_inject
   ```
4. Check Wayland InputCapture portal:
   - On Hyprland / Sway, verify `xdg-desktop-portal-hyprland` or `xdg-desktop-portal-wlr` is active.

---

## Settings Persistence (Nix / Home Manager)

If options like "Auto-accept files from phone" or Universal Control screen placement fail to persist, ensure `~/.local/share/vortex/` is writable. On NixOS / Home Manager systems, Vortex automatically unlinks previous read-only nix-store symlinks before writing user preferences to prevent `EROFS` errors.

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
