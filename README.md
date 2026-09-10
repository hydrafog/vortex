<p align="center">
  <img src="assets/vortex_logo.png" width="128" alt="Vortex Logo">
</p>

# Vortex

![Version](https://img.shields.io/badge/version-1.0.0--beta-blue.svg)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](./LICENSE)
[![Rust](https://img.shields.io/badge/Rust-%E2%89%A5%201.77-orange.svg)](https://www.rust-lang.org)
[![Tauri](https://img.shields.io/badge/Tauri-2.0-blue.svg)](https://tauri.app)
[![Android](https://img.shields.io/badge/Android-%E2%89%A5%2010.0-green.svg)](https://developer.android.com)

Vortex links a Linux desktop and an Android phone over the local network. Clipboard, mouse and keyboard, files, audio, notifications, and screen share move directly between the two devices, encrypted end to end. There is no cloud account and no relay server.

> [!NOTE]
> The documentation and codebase guides for this project were written and maintained with the assistance of AI agents.

> [!WARNING]
> **Beta Software**: Vortex is currently under active development. Features, protocol definitions, and platform integrations are actively being tested and refined.

---

## Documentation index

### Getting started

| Document | Description |
| :--- | :--- |
| [Installation guide](docs/getting-started/installation.md) | Nix flake, Direnv, and platform prerequisites |
| [Pairing and security](docs/getting-started/pairing.md) | BLE discovery, Noise handshake, and 3-emoji SAS check |

### System architecture

| Document | Description |
| :--- | :--- |
| [Architecture overview](docs/architecture/overview.md) | Core daemon (`vortex-l3d`), Tauri GUI, Android app, and inject helper |
| [Wire and encryption protocols](docs/architecture/protocols.md) | Protobuf contracts, ChaCha20-Poly1305 framing, and BLE / Wi-Fi transports |

### Core features

| Document | Description |
| :--- | :--- |
| [Universal control](docs/features/universal-control.md) | Wayland `libei` / InputCapture portal, edge crossing, and Android `/dev/uinput` injection |
| [Screen mirroring and casting](docs/features/screen-mirroring.md) | H.264 GStreamer decoding pipeline and Wayland screencasting |
| [Universal clipboard](docs/features/clipboard-sync.md) | Clipboard sync with password manager filtering |
| [Smart audio handoff](docs/features/smart-audio.md) | MPRIS media pause, Bluetooth audio tracking, and call priority |
| [Instant file sharing](docs/features/file-sharing.md) | Local Wi-Fi Direct drops, folder packaging, and file manager integration |

### Development and maintenance

| Document | Description |
| :--- | :--- |
| [Environment and tooling](docs/development/environment.md) | Nix shell, Lefthook pre-commit checks, and formatting standards |
| [Security and compliance](docs/security-compliance/security.md) | Local-first threat model, Noise XX crypto, and sandbox privileges |
| [Troubleshooting guide](docs/operations/troubleshooting.md) | Diagnostics, BlueZ/BLE reconnection, ADB permissions, and Wayland display fixes |

---

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE).
