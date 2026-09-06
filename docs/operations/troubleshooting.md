# Troubleshooting guide

Fixes for problems people hit with Vortex.

## Bluetooth discovery and pairing

### Device not visible during BLE scan
1. Ensure Bluetooth is powered on:
   ```bash
   bluetoothctl show
   ```
2. Verify BlueZ daemon is running:
   ```bash
   systemctl status bluetooth
   ```
3. Ensure location and nearby permissions are granted on the Android app (required by Android OS for BLE scanning).

### Pairing fails or aborts
- A Bluetooth timeout, GATT failure, or BlueZ agent conflict shows Pairing failed.
- An explicit emoji reject shows Pairing canceled for safety.
- On KDE Plasma, Bluedevil can intercept pairing. Vortex registers a Just Works agent with BlueZ. When the desktop prompts instead, restart Bluetooth or disconnect third-party connectors such as KDE Connect. Check the controller with `bluetoothctl show`.
- When BlueZ keeps a bonded classic (BR/EDR) audio profile (A2DP or HFP) for the phone, it refuses GATT over BLE. Vortex reports this error when discovery times out:
  `BlueZ kept the classic (BR/EDR) bearer, so no GATT service is reachable. This phone is also paired to this laptop as a Bluetooth *audio* device, and BlueZ always prefers the bonded bearer. Unpair it as an audio device (bluetoothctl remove <addr>), then pair Vortex.`
- Run `bluetoothctl remove <addr>` to remove the audio device entry in BlueZ, then pair again inside Vortex.

---

## Universal control and input

### Cursor does not cross the edge or mirror fails
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
   - On Hyprland or Sway, verify `xdg-desktop-portal-hyprland` or `xdg-desktop-portal-wlr` is active.

---

## Settings persistence (Nix and Home Manager)

When options like Auto-accept files from phone or Universal Control placement do not stick, check that `~/.local/share/vortex/` is writable. On NixOS and Home Manager, Vortex replaces the read-only store symlink with a user file before it writes, so preferences survive.

---

## Wayland and X11 display

- On native Wayland, set `GDK_BACKEND=wayland` when running outside GNOME fractional scaling.
- When window controls misalign under fractional scaling, try `GDK_BACKEND=x11`.

---

## Logs and diagnostics

- Run daemon with detailed tracing:
  ```bash
  RUST_LOG=debug cargo run --manifest-path linux/daemon/Cargo.toml
  ```
- View desktop app logs:
  ```bash
  tail -f ~/.local/share/vortex/vortex.log
  ```
