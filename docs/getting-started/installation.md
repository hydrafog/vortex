# Installation guide

You can run Vortex from the Nix flake with Direnv, or build it with native scripts.

## Nix flake and Direnv (recommended)

The flake pins Rust, Tauri, Node.js, Protobuf, GStreamer, and Android dependencies.

### 1. Enable the environment with Direnv

In the repository root:

```bash
direnv allow
```

Or enter the shell directly:

```bash
nix develop
```

Direnv loads development libraries (`webkitgtk_4_1`, `gtk3`, `gst_all_1`, `libayatana-appindicator`, `protobuf`, `android-tools`) and sets `LD_LIBRARY_PATH` and `PKG_CONFIG_PATH`.

### 2. Frontend dependencies

Install frontend packages using `pnpm`:

```bash
cd linux/ui-tauri
pnpm install
```

### 3. Build and run the desktop client

From the project root:

```bash
./linux/run.sh
```

Or run the daemon directly:

```bash
cargo run --manifest-path linux/daemon/Cargo.toml
```

## Resident tray behavior

The Linux client runs as a resident tray application. The `vortex` tray icon and menu form the primary surface. The main window stays hidden during resident start while background workers for sync, clipboard, and notifications stay alive.

Startup visibility follows a single rule. Tray enabled allows a hidden resident start. Tray disabled forces a visible main window so the application starts with at least one visible surface. The `--hidden`, `--clipboard`, and `--share` flags request background start. The `--no-tray` flag and the `enabled` field in `tray.json` report whether the tray is available. `is_tray_enabled` combines both sources. Closing the main window hides the window and keeps the tray resident.

Single-instance arguments resolve through the same surface. The `--sms` path records a recent alert in the tray, shows a toast, and copies the number to the local clipboard for reply from the phone. The `--sms-send` path records a recent alert, shows a toast, and sends in the background. The `--share` path forwards file paths to the transfer pipeline.

## Autostart

Autostart launches the resident tray on login. The Home Manager module exposes the option directly.

### 4. Binary cache

To skip local builds of Rust and WebKitGTK, point Nix at the Cachix cache:

In NixOS (`/etc/nixos/configuration.nix` or flake):

```nix
nix.settings = {
  substituters = [ "https://hydrafog.cachix.org" ];
  trusted-public-keys = [
    "hydrafog.cachix.org-1:UKlU4vpY+vHoft1i+OAVBaVnFaq6udjRM8FPUSAPKLw="
  ];
};
```

### 5. NixOS and Home Manager module

Add Vortex to your flake inputs:

```nix
inputs.vortex.url = "github:hydrafog/vortex";
```

#### Home Manager

Import the module and enable the service:

```nix
{ inputs, ... }:
{
  imports = [ inputs.vortex.homeManagerModules.default ];

  services.vortex = {
    enable = true;
    backend = "wayland";     # "wayland", "x11", or "auto"
    autostart = true;        # XDG autostart on login
    disableDmabuf = true;    # Recommended for WebKitGTK on Wayland

    notifications.enable = true;
    clipboardSync.enable = true;
    smartAudioHandoff.enable = true;
    phoneCompanion.enable = true;
    browsingHandoff.enable = true;
    notesSync.enable = true;
    fileSharing.enable = true;
  };
}
```

The `autostart` field installs an XDG autostart entry for resident tray launch. Notification access and background permissions cover banner delivery and clipboard handoff on first login.

---

## Android client setup

1. Enable **Developer options** and **USB debugging** on your Android device.
2. Build and install the APK via Gradle:

   ```bash
   cd android
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

3. Grant notification access and background permissions when prompted in the app. Notification and status bar glyphs use simplified Solar Linear shapes with white fill on viewport 24, so they stay legible at small sizes. Launcher icons use the Solar brand mark at each density.

## See also

- `docs/architecture/overview.md` describes the tray-first surface and daemon boundary.
- `docs/operations/troubleshooting.md` covers tray icon and banner diagnostics.
- `docs/features/file-sharing.md` describes the tray send path.
- `docs/features/clipboard-sync.md` describes SMS login-code clipboard pass-through.
