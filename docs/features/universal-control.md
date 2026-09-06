# Universal control

Move the desktop pointer past the assigned screen edge and it types and clicks on the Android phone. Move it back and control returns to the laptop.

## Architecture

```
[Laptop Screen] --- (Barrier Crossing) ---> [InputCapture Portal (libei)]
                                                    │
                                                    ▼
                                          [vortex-ui-tauri (reis)]
                                                    │ (Encrypted Protobuf)
                                                    ▼
                                            [Android Device]
                                                    │
                                                    ▼
                                      [/data/local/tmp/vortex_inject]
                                                    │
                                                    ▼
                                              [/dev/uinput]
```

## Desktop input capture (Wayland)

X11 root polling does not work on Wayland, so Vortex registers edge barriers through the InputCapture portal:
- The portal comes from `xdg-desktop-portal`. Vortex talks to it with `reis`, the Rust libei client.
- When the pointer hits the edge assigned to the phone, the portal holds input and sends relative moves and key codes to Vortex.
- While held, the desktop cursor stays hidden through the GNOME Shell extension or compositor hook.

## Android input injection (`vortex_inject`)

Android does not let normal apps inject input. Vortex ships a small helper instead:
1. Vortex copies the 20 KB `vortex_inject` binary to `/data/local/tmp/`.
2. It starts the binary as the `shell` user over ADB.
3. The binary opens `/dev/uinput` and registers a virtual touch screen, a virtual mouse, and a virtual keyboard.
4. Trackpad moves and key events from the desktop arrive as kernel input on Android, usually under 10 ms.

## Prerequisites and setup

Universal Control needs:
1. A Wayland compositor with the InputCapture portal (`xdg-desktop-portal` with InputCapture on GNOME 45+, KDE Plasma 6.1+, Hyprland, or Sway).
2. ADB access to the phone over USB or Wi-Fi. The desktop reaches the helper at `/data/local/tmp/vortex_inject` through an ADB forwarded socket.

### Declarative configuration (Home Manager)

Universal Control can be declared in Home Manager configuration:

```nix
services.vortex = {
  enable = true;
  universalControl = {
    enable = true;
    placement = "right"; # "left", "right", "top", or "bottom"
  };
};
```
