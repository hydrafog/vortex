# Universal Clipboard

Vortex provides bidirectional, real-time clipboard synchronization for text and images between Linux and Android devices.

## Wayland and X11 Integration

On Linux, clipboard synchronization commonly breaks on Wayland sessions when using generic X11-only libraries. Vortex handles this robustly:
- Utilizes `arboard` built with the `wayland-data-control` feature.
- Communicates directly with Wayland compositors supporting the `wlr-data-control` protocol (wlroots, Sway, Hyprland) or falls back to X11 on GNOME/KDE.
- Integrates `wl-clipboard-rs` for zero-delay selection updates.

## Privacy & Password Manager Protection

To prevent sensitive passwords, credit card numbers, and 2FA tokens from inadvertently syncing over the network:
1. When a clipboard change occurs, Vortex inspects MIME selection targets before reading data.
2. Checks for privacy hints:
   - `x-kde-passwordManagerHint` (KeePassXC, Bitwarden, 1Password)
   - `application/x-password`
3. If a password manager hint is present, Vortex silently drops synchronization for that item.
4. Transient SMS login codes received on the phone bypass the block and paste automatically to the desktop clipboard.
