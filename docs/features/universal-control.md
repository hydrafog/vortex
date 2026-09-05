# Universal Control

Universal Control lets you move your desktop mouse pointer seamlessly across the screen barrier and control your Android tablet or phone using your laptop keyboard and trackpad.

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

## Desktop Input Capture (Wayland)
On Wayland sessions (GNOME, Hyprland, Sway), pointer grab cannot be achieved via X11 root window polling. Vortex utilizes:
- **`xdg-desktop-portal` InputCapture portal**: Employs `reis` (Rust libei client) to register edge barriers.
- When the cursor hits the screen border assigned to the phone (left, right, top, or bottom), the portal traps input events and routes relative pointer movements and scancodes to Vortex.
- While captured, the desktop cursor is inhibited via the GNOME Shell extension / compositor hook.

## Android Input Injection (`vortex_inject`)
Android restricts raw input injection from non-system apps. Vortex solves this without rooting or vendor-specific Mi account bypasses:
1. Pushes a standalone 20 KB native binary `vortex_inject` to `/data/local/tmp/`.
2. Spawns the binary under the `shell` user privilege via ADB.
3. `vortex_inject` opens `/dev/uinput` to register:
   - A virtual multitouch screen
   - A virtual mouse
   - A virtual hardware keyboard
4. Relative desktop trackpad movements and key events translate to native kernel input events on Android with sub-10ms latency.

## Prerequisites & Setup

Universal Control operates with two system components:
1. **Wayland InputCapture Portal**: A Wayland compositor supporting `xdg-desktop-portal` with the `InputCapture` portal interface (GNOME 45+, KDE Plasma 6.1+, Hyprland, or Sway).
2. **Android ADB Debugging**: USB debugging or wireless ADB authorized on the Android device. The desktop client communicates with the `vortex_inject` helper process deployed to `/data/local/tmp/vortex_inject` over an ADB port-forwarded socket.

### Declarative Configuration (Home Manager)

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
