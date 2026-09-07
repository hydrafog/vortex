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

- A Bluetooth timeout, GATT failure, or BlueZ agent conflict shows Pairing failed. Vortex retries the GATT connect 3 times before failing, so a single transient miss does not abort pairing.
- An explicit emoji reject shows Pairing canceled for safety. Both screens allow 120 seconds to compare the three emoji.
- On KDE Plasma, Bluedevil can intercept pairing. Vortex registers a Just Works agent with BlueZ. When the desktop prompts instead, restart Bluetooth or disconnect third-party connectors such as KDE Connect. Check the controller with `bluetoothctl show`.
- When BlueZ keeps a bonded classic (BR/EDR) audio profile (A2DP or HFP) for the phone, it refuses GATT over BLE. Vortex reports this error with the peer address and a one-click Remove Bond fix:
  `BlueZ kept the classic (BR/EDR) bearer, so no GATT service is reachable. This phone is also paired to this laptop as a Bluetooth *audio* device, and BlueZ always prefers the bonded bearer. Unpair it as an audio device (bluetoothctl remove <addr>), then pair Vortex.`
- Run `bluetoothctl remove <addr>` or the Vortex Remove Bond action to remove the audio device entry in BlueZ, then pair again inside Vortex. Vortex trust in the keyring is kept.

---

## Tray and notifications

The tray is the primary resident surface. The main window inventory holds `/`, `/settings`, and `/clipboard`. Diagnostics start at the tray icon, continue through banner delivery, and end at the triage path.

### Tray icon visibility

The tray icon reflects `is_tray_enabled`, which combines the `--no-tray` flag with the `enabled` field in `tray.json`. The failure mode is a missing icon with a hidden main window. The checks share one pattern: confirm the tray is enabled, then confirm the shell shows it.

- On GNOME, the icon requires an AppIndicator extension. The notification path uses a `gdbus` child process on GNOME and direct `zbus` elsewhere. When the icon is absent on GNOME, enable the AppIndicator extension, restart the shell, and relaunch without `--no-tray`.
- On KDE Plasma, the system tray shows the icon directly. When the icon is absent, check the system tray visibility settings and the `libayatana-appindicator` dependency from the Direnv environment.
- On Wayland compositors without a tray host (plain wlroots, Sway, Hyprland), the icon has no host to attach to. Banner and toast delivery still works through `org.freedesktop.Notifications`. The main window remains reachable through `/settings` and `/clipboard`.
- When the tray is disabled, the main window forces itself visible on startup so the application starts with at least one visible surface. When the tray is enabled, `--hidden`, `--clipboard`, and `--share` start resident with a hidden window.

### Banner persistence

Banners arrive through `org.freedesktop.Notifications` with `show` and `show_call_banner` delivery, `watch_actions` capability probing, and `watch_closed` dismissal tracking. The failure mode is a banner that never appears or disappears before triage. The checks share one pattern: confirm the notification toggle, then confirm the server capability.

- When all banners are absent, confirm the notification toggle in the tray menu and the `notifications.enable` setting. The daemon logs a suppressed event at info level with a redacted payload.
- When call banners vanish early, check the server `actions` capability. A server without `actions` degrades Accept, Decline, and Reply into timeout-as-declined, which the daemon logs at warning level. The tray menu carries compensating answer and decline entries.
- Repeated alerts update in place through a `replace-id` key derived from the phone key. Catch-up resync debounces at 1500 ms with a 90 s minimum interval after `BLE` drops, so a burst of duplicates points to transport replay rather than tray state.

### Triage paths

Each alert carries a triage path that avoids any full page. SMS and call clicks record a recent alert in the tray and show a toast. Call toasts direct to Answer and Decline in the tray menu. Message toasts direct to Copy login code in the tray menu.

- When a click does nothing visible, open the tray menu and read the Recent entry. The menu keeps the last five alerts with truncated titles. A fresh entry there means delivery works and only the banner action was lost.
- When an SMS code is missing from the clipboard, use Copy login code in the tray menu. The clipboard leg belongs to the SMS delivery offer, so clicks never overwrite it with stale text.
- External opens validate against an allowlist (`https://wa.me/`, Gmail, Outlook, Yahoo, Proton webmail). Non-allowlisted URLs stay closed and log a warning. Desktop app launches accept `.desktop` paths only.

### Wayland and X11 display

- On native Wayland, set `GDK_BACKEND=wayland` when running outside GNOME fractional scaling.
- When window controls misalign under fractional scaling, try `GDK_BACKEND=x11`.

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

## Logs and diagnostics

- Run daemon with detailed tracing:

  ```bash
  RUST_LOG=debug cargo run --manifest-path linux/daemon/Cargo.toml
  ```

- View desktop app logs:

  ```bash
  tail -f ~/.local/share/vortex/vortex.log
  ```

## See also

- `docs/architecture/overview.md` describes the tray-first surface and daemon boundary.
- `docs/getting-started/installation.md` describes resident tray defaults and autostart.
- `docs/features/file-sharing.md` describes the tray send path.
- `docs/features/clipboard-sync.md` describes SMS login-code clipboard pass-through.
