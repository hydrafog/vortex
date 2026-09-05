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
