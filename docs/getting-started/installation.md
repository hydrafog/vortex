# Installation Guide

Vortex provides reproducible setup via Nix Flake and Direnv, as well as native scripts for traditional environments.

## Nix Flake & Direnv (Recommended)

Vortex is fully packaged with a Nix flake providing all Rust, Tauri, Node.js, Protobuf, GStreamer, and Android dependencies.

### 1. Enable Environment via Direnv
In the repository root:
```bash
direnv allow
```
Or manually enter the dev shell:
```bash
nix develop
```

This ensures all required development libraries (`webkitgtk_4_1`, `gtk3`, `gst_all_1`, `libayatana-appindicator`, `protobuf`, `android-tools`) and environment variables (`LD_LIBRARY_PATH`, `PKG_CONFIG_PATH`) are loaded.

### 2. Frontend Dependencies
Install frontend packages using `pnpm`:
```bash
cd linux/ui-tauri
pnpm install
```

### 3. Build & Run Desktop Client
From the project root:
```bash
./linux/run.sh
```
Or run the daemon directly:
```bash
cargo run --manifest-path linux/daemon/Cargo.toml
```

### 4. Binary Cache (Instant Pre-built Binaries)
To avoid compiling Rust and WebKitGTK locally, configure the Cachix binary cache:

In NixOS (`/etc/nixos/configuration.nix` or flake):
```nix
nix.settings = {
  substituters = [ "https://hydrafog.cachix.org" ];
  trusted-public-keys = [
    "hydrafog.cachix.org-1:UKlU4vpY+vHoft1i+OAVBaVnFaq6udjRM8FPUSAPKLw="
  ];
};
```

### 5. NixOS & Home Manager Module Integration
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

---


## Android Client Setup

1. Enable **Developer options** and **USB debugging** on your Android device.
2. Build and install the APK via Gradle:
   ```bash
   cd android
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
3. Grant notification access and background permissions when prompted in the app. Notification plus status bar glyphs use simplified Solar Linear silhouettes (white fill, viewport 24) for legibility at small sizes; no setup step changes.
