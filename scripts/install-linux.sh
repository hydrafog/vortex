#!/usr/bin/env bash
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
UI="$REPO/linux/ui-tauri"
BIN_SRC="$UI/src-tauri/target/release/vortex-ui-tauri"
ICON_SRC="$UI/src-tauri/icons/icon.png"

BIN_DIR="$HOME/.local/bin"
ICON_ROOT="${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor"
APP_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
AUTOSTART_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/autostart"
BIN_DST="$BIN_DIR/vortex-ui-tauri"

SKIP_DEPS=0
DEPS_ARGS=()
for a in "$@"; do
  case "$a" in
    --skip-deps) SKIP_DEPS=1 ;;
    --yes)       DEPS_ARGS+=(--yes) ;;
    --ask)       DEPS_ARGS+=(--ask) ;;
  esac
done

if [ "$SKIP_DEPS" -eq 0 ]; then
  bash "$REPO/linux/packaging/install-deps.sh" "${DEPS_ARGS[@]}" || \
    echo "⚠ dependency step had issues — continuing; the build will flag anything truly missing."
  [ -f "$HOME/.cargo/env" ] && . "$HOME/.cargo/env"
else
  echo "▶ --skip-deps: assuming system dependencies are already installed."
fi

echo "▶ installing UI dependencies…"
( cd "$UI"
  if command -v pnpm >/dev/null 2>&1; then
    pnpm install --frozen-lockfile || pnpm install
  else
    echo "  · pnpm not found — using npm with --legacy-peer-deps"
    npm install --legacy-peer-deps
  fi )

echo "▶ building prod binary (embeds UI; --no-bundle skips packaging)…"
( cd "$UI" && npm run tauri build -- --no-bundle )
[ -x "$BIN_SRC" ] || { echo "✗ build produced no binary at $BIN_SRC" >&2; exit 1; }

echo "▶ installing files (user-level, no sudo)…"
mkdir -p "$BIN_DIR" "$APP_DIR" "$AUTOSTART_DIR"
install -m755 "$BIN_SRC" "$BIN_DST"

if command -v python3 >/dev/null 2>&1 && python3 -c 'import PIL' 2>/dev/null; then
  for s in 32 48 64 128 256 512; do
    d="$ICON_ROOT/${s}x${s}/apps"; mkdir -p "$d"
    python3 -c "from PIL import Image; Image.open('$ICON_SRC').convert('RGBA').resize(($s,$s), Image.LANCZOS).save('$d/vortex-ui-tauri.png')"
  done
else
  d="$ICON_ROOT/256x256/apps"; mkdir -p "$d"
  install -m644 "$ICON_SRC" "$d/vortex-ui-tauri.png"
fi
command -v gtk-update-icon-cache >/dev/null 2>&1 && gtk-update-icon-cache -f -t "$ICON_ROOT" 2>/dev/null || true

cat > "$APP_DIR/vortex-ui-tauri.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Vortex
Comment=Phone companion — calls, messages, notifications and earbuds hand-off
Exec=env GDK_BACKEND=x11 WEBKIT_DISABLE_DMABUF_RENDERER=1 $BIN_DST
Icon=vortex-ui-tauri
Terminal=false
Categories=Utility;Network;
StartupNotify=false
StartupWMClass=vortex-ui-tauri
EOF

cat > "$AUTOSTART_DIR/vortex-ui-tauri.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Vortex
Comment=Phone companion — starts with the session
Exec=env GDK_BACKEND=x11 WEBKIT_DISABLE_DMABUF_RENDERER=1 $BIN_DST --hidden
Icon=vortex-ui-tauri
Terminal=false
Categories=Utility;Network;
StartupNotify=false
StartupWMClass=vortex-ui-tauri
X-GNOME-Autostart-enabled=true
X-GNOME-Autostart-Delay=8
EOF

command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database "$APP_DIR" 2>/dev/null || true

NAUTILUS_EXT_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/nautilus-python/extensions"
mkdir -p "$NAUTILUS_EXT_DIR"
install -m644 "$REPO/linux/packaging/nautilus/vortex.py" "$NAUTILUS_EXT_DIR/vortex.py"
nautilus -q >/dev/null 2>&1 || true

if command -v dolphin >/dev/null 2>&1; then
  for KDE_SM_DIR in \
      "${XDG_DATA_HOME:-$HOME/.local/share}/kio/servicemenus" \
      "${XDG_DATA_HOME:-$HOME/.local/share}/kservices5/ServiceMenus"; do
    mkdir -p "$KDE_SM_DIR"
    cat > "$KDE_SM_DIR/vortex-share.desktop" <<EOF
[Desktop Entry]
Type=Service
MimeType=all/all;
Actions=vortexShare;
X-KDE-Priority=TopLevel

[Desktop Action vortexShare]
Name=Share via Vortex
Icon=vortex-ui-tauri
Exec=$BIN_DST --share %F
EOF
    chmod +x "$KDE_SM_DIR/vortex-share.desktop"
  done
  echo "▶ KDE Dolphin 'Share via Vortex' installed."
fi

GNOME_EXT_UUID="vortex-live@vortex"
GNOME_EXT_SRC="$REPO/linux/gnome-extension/$GNOME_EXT_UUID"
if command -v gnome-shell >/dev/null 2>&1 \
   && printf '%s' "${XDG_CURRENT_DESKTOP:-}" | grep -qi gnome \
   && [ -d "$GNOME_EXT_SRC" ]; then
  GNOME_EXT_DST="${XDG_DATA_HOME:-$HOME/.local/share}/gnome-shell/extensions/$GNOME_EXT_UUID"
  mkdir -p "$GNOME_EXT_DST"
  install -m644 "$GNOME_EXT_SRC"/metadata.json "$GNOME_EXT_SRC"/extension.js "$GNOME_EXT_SRC"/stylesheet.css "$GNOME_EXT_DST/"
  if command -v gnome-extensions >/dev/null 2>&1 \
     && gnome-extensions enable "$GNOME_EXT_UUID" >/dev/null 2>&1; then
    echo "▶ GNOME pill extension installed + enabled."
  elif command -v gsettings >/dev/null 2>&1; then
    if gsettings get org.gnome.shell enabled-extensions | grep -q "'$GNOME_EXT_UUID'"; then
      echo "ℹ GNOME pill extension updated; already queued to enable — log out/in once."
    elif python3 - "$GNOME_EXT_UUID" <<'PY' 2>/dev/null
import subprocess, sys, ast
uuid = sys.argv[1]
cur = subprocess.check_output(
    ["gsettings", "get", "org.gnome.shell", "enabled-extensions"], text=True).strip()
lst = ast.literal_eval(cur.replace("@as ", "", 1))
lst.append(uuid)
subprocess.check_call(["gsettings", "set", "org.gnome.shell", "enabled-extensions",
                       "[" + ", ".join("'%s'" % e for e in lst) + "]"])
PY
    then
      echo "▶ GNOME pill extension installed and marked enabled — log out/in once to see the pill."
    else
      echo "ℹ GNOME pill extension installed; enable it with: gnome-extensions enable $GNOME_EXT_UUID"
    fi
  else
    echo "ℹ GNOME pill extension installed (enable with: gnome-extensions enable $GNOME_EXT_UUID)."
  fi
else
  echo "ℹ Not a GNOME session — skipping the pill extension (the app uses its tray fallback here)."
fi

echo "▶ restarting the app on the installed binary…"
pkill -9 -x vortex-ui-tauri 2>/dev/null || true
sleep 1
setsid env GDK_BACKEND=x11 WEBKIT_DISABLE_DMABUF_RENDERER=1 "$BIN_DST" >/dev/null 2>&1 < /dev/null &
disown 2>/dev/null || true

case ":$PATH:" in
  *":$BIN_DIR:"*) ;;
  *) echo "ℹ note: $BIN_DIR is not on your PATH (the app still runs; only matters for typing 'vortex-ui-tauri')." ;;
esac

echo "✓ installed. Running now, and it will auto-start on every login."
