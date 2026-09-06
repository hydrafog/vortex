# Universal clipboard

Copied text and images travel both ways between Linux and Android as you copy.

## Wayland and X11 integration

X11-only clipboard libraries miss updates on Wayland. Vortex reads the selection directly:
- It builds `arboard` with the `wayland-data-control` feature.
- On wlroots, Sway, and Hyprland it talks `wlr-data-control`. On GNOME and KDE it falls back to X11.
- It uses `wl-clipboard-rs` for selection updates without polling delay.

## Privacy and password manager protection

Passwords stay off the network:
1. On each clipboard change, Vortex looks at MIME targets before it reads data.
2. It looks for `x-kde-passwordManagerHint` (KeePassXC, Bitwarden, 1Password) and `application/x-password`.
3. With either hint present, Vortex skips that item.
4. SMS login codes from the phone still pass through and land in the desktop clipboard.
