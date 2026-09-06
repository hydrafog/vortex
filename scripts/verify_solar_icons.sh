#!/usr/bin/env bash
# Verify Solar Linear swap: no leftovers, artwork present, docs updated.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
fail() { echo "verify_solar_icons: FAIL: $1" >&2; exit 1; }

# No prior icon package remains in web sources.
if rg -q 'lucide-vue-next' linux/ui-tauri/src; then
  fail "lucide-vue-next import remains in linux/ui-tauri/src"
fi
echo "ok: no lucide-vue-next in linux/ui-tauri/src"

# Shared Solar adapter present with locked Linear weight.
test -f linux/ui-tauri/src/lib/solarIcons.ts || fail "missing linux/ui-tauri/src/lib/solarIcons.ts"
rg -q 'Linear' linux/ui-tauri/src/lib/solarIcons.ts || fail "solarIcons.ts missing Linear marker"
rg -q 'SolarDevices' linux/ui-tauri/src/lib/solarIcons.ts || fail "solarIcons.ts missing SolarDevices"
! rg -qi 'Bold Duotone|Bold|Broken' linux/ui-tauri/src/lib/solarIcons.ts || fail "mixed Solar weight in solarIcons.ts"
echo "ok: Solar Vue adapter present with Linear weight"

# No replaced Compose Icons usage remains in migrated screens.
for f in \
  android/app/src/main/java/com/vortex/a3/ui/screens/HomeScreen.kt \
  android/app/src/main/java/com/vortex/a3/ui/screens/SettingsScreen.kt \
  android/app/src/main/java/com/vortex/a3/ui/screens/NotesScreen.kt \
  android/app/src/main/java/com/vortex/a3/ui/components/Common.kt \
  android/app/src/main/java/com/vortex/a3/ui/components/PeerDeviceCard.kt \
  android/app/src/main/java/com/vortex/a3/ui/components/EarbudsCard.kt \
  android/app/src/main/java/com/vortex/a3/ui/components/EarbudsPicker.kt; do
  test -f "$f" || fail "missing $f"
  if rg -q 'androidx\.compose\.material\.icons|Icons\.Outlined|Icons\.Filled|Icons\.AutoMirrored' "$f"; then
    fail "material Icons remains in $f"
  fi
  rg -q 'SolarIcons' "$f" || fail "SolarIcons missing in $f"
done
echo "ok: Android screens use SolarIcons with no material Icons leftovers"

# Shared Android registry present with state mapping.
test -f android/app/src/main/java/com/vortex/a3/ui/icons/SolarIcons.kt || fail "missing SolarIcons.kt"
rg -q 'batteryIconFor' android/app/src/main/java/com/vortex/a3/ui/icons/SolarIcons.kt || fail "SolarIcons.kt missing batteryIconFor"
rg -q 'lockIconFor' android/app/src/main/java/com/vortex/a3/ui/icons/SolarIcons.kt || fail "SolarIcons.kt missing lockIconFor"
echo "ok: SolarIcons registry present"

# Drawable names present with viewport 24 and fill.
for f in \
  android/app/src/main/res/drawable/ic_notification_vortex.xml \
  android/app/src/main/res/drawable/ic_notification_laptop.xml \
  android/app/src/main/res/drawable/ic_notification_earbuds.xml \
  android/app/src/main/res/drawable/ic_notification_clipboard.xml \
  android/app/src/main/res/drawable/ic_notification_lock.xml \
  android/app/src/main/res/drawable/ic_notification_lock_open.xml \
  android/app/src/main/res/drawable/ic_notification_bell.xml \
  android/app/src/main/res/drawable/ic_notification_mirror.xml \
  android/app/src/main/res/drawable/ic_notification_note.xml \
  android/app/src/main/res/drawable/ic_notification_download.xml \
  android/app/src/main/res/drawable/ic_notification_download_done.xml \
  android/app/src/main/res/drawable/ic_notification_chat.xml \
  android/app/src/main/res/drawable/ic_notification_camera.xml \
  android/app/src/main/res/drawable/ic_vortex_logo_vector.xml \
  android/app/src/main/res/drawable/ic_vortex_media_prev.xml \
  android/app/src/main/res/drawable/ic_vortex_media_play.xml \
  android/app/src/main/res/drawable/ic_vortex_media_pause.xml \
  android/app/src/main/res/drawable/ic_vortex_media_next.xml; do
  test -f "$f" || fail "missing $f"
  rg -q 'viewportWidth="24"' "$f" || fail "bad viewport in $f"
  rg -q 'fill' "$f" || fail "missing fill in $f"
done
echo "ok: Android drawables present with viewport 24"

# No android.R.drawable remnants in Android java sources.
if rg -q 'android\.R\.drawable' android/app/src/main/java; then
  fail "android.R.drawable remnants found in android/app/src/main/java"
fi
echo "ok: zero android.R.drawable remnants in Android sources"

# Brand source plus exports present.
test -f assets/vortex_solar_source.svg || fail "missing assets/vortex_solar_source.svg"
test -f assets/SOLAR_LICENSE.txt || fail "missing assets/SOLAR_LICENSE.txt"
python3 -c "import xml.etree.ElementTree as ET; ET.parse('assets/vortex_solar_source.svg')" || fail "brand SVG parse failed"
for f in \
  assets/vortex_logo.png \
  linux/ui-tauri/src/assets/vortex_logo.png \
  android/app/src/main/res/drawable/vortex_logo.png \
  linux/daemon/src/assets/vortex_icon.png; do
  test -f "$f" || fail "missing $f"
done
echo "ok: brand source and PNG copies present"

# Tauri bundle artwork present.
for f in \
  linux/ui-tauri/src-tauri/icons/icon.png \
  linux/ui-tauri/src-tauri/icons/32x32.png \
  linux/ui-tauri/src-tauri/icons/64x64.png \
  linux/ui-tauri/src-tauri/icons/128x128.png \
  "linux/ui-tauri/src-tauri/icons/128x128@2x.png" \
  linux/ui-tauri/src-tauri/icons/tray.png \
  linux/ui-tauri/public/favicon.svg; do
  test -f "$f" || fail "missing $f"
done
python3 -c "import xml.etree.ElementTree as ET; ET.parse('linux/ui-tauri/public/favicon.svg')" || fail "favicon parse failed"
echo "ok: Tauri bundle artwork present"

# Mipmap launcher icons and foregrounds present.
for f in \
  android/app/src/main/res/mipmap-mdpi/ic_launcher_foreground.png \
  android/app/src/main/res/mipmap-hdpi/ic_launcher_foreground.png \
  android/app/src/main/res/mipmap-xhdpi/ic_launcher_foreground.png \
  android/app/src/main/res/mipmap-xxhdpi/ic_launcher_foreground.png \
  android/app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png \
  android/app/src/main/res/mipmap-mdpi/ic_launcher.png \
  android/app/src/main/res/mipmap-mdpi/ic_launcher_round.png \
  android/app/src/main/res/mipmap-hdpi/ic_launcher.png \
  android/app/src/main/res/mipmap-hdpi/ic_launcher_round.png \
  android/app/src/main/res/mipmap-xhdpi/ic_launcher.png \
  android/app/src/main/res/mipmap-xhdpi/ic_launcher_round.png \
  android/app/src/main/res/mipmap-xxhdpi/ic_launcher.png \
  android/app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png \
  android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png \
  android/app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png; do
  test -f "$f" || fail "missing $f"
done
echo "ok: mipmap launcher icons and foregrounds present"

# Helper binary and runtime mirroring unchanged.
rg -q 'vortex_inject' linux/ui-tauri/src-tauri/src/mirror_inject.rs || fail "vortex_inject reference missing"
test -f linux/daemon/src/core/icon_cache.rs || fail "missing icon_cache.rs"
echo "ok: helper binary and icon cache references intact"

# Docs mention Solar artwork pipeline.
rg -qi 'solar' docs/architecture/overview.md || fail "docs/architecture/overview.md missing Solar note"
rg -qi 'solar|notification.*icon|status.*icon' docs/getting-started/installation.md || fail "docs/getting-started/installation.md missing icon note"
echo "ok: docs updated"

echo "verify_solar_icons: PASS"
