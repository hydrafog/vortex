# Vortex documentation

This is the documentation for Vortex. It describes how a Linux desktop and an Android phone exchange clipboard, input, files, screen video, audio state, and notifications over the local network.

## Structure

- [Getting started](getting-started/installation.md): installation, environment setup, and device pairing.
  - [Installation guide](getting-started/installation.md)
  - [Pairing and security](getting-started/pairing.md)
- [System architecture](architecture/overview.md): components, daemons, and wire protocols.
  - [Architecture overview](architecture/overview.md)
  - [Wire and encryption protocols](architecture/protocols.md)
- [Features](features/universal-control.md): what each feature does and how it works.
  - [Universal control](features/universal-control.md)
  - [Screen mirroring](features/screen-mirroring.md)
  - [Universal clipboard](features/clipboard-sync.md)
  - [Smart audio handoff](features/smart-audio.md)
  - [Instant file sharing](features/file-sharing.md)
- [Development](development/environment.md): workflow, Nix flakes, tests, and formatting.
  - [Environment and tooling](development/environment.md)
- [Security and compliance](security-compliance/security.md): threat model, Noise cryptography, and sandboxing.
  - [Security architecture](security-compliance/security.md)
- [Operations and troubleshooting](operations/troubleshooting.md): diagnostics, BLE debugging, and common fixes.
  - [Troubleshooting guide](operations/troubleshooting.md)
