{
  lib,
  stdenv,
  pkg-config,
  rustPlatform,
  wrapGAppsHook3,
  cargo-tauri,
  nodejs,
  pnpm,
  fetchPnpmDeps,
  pnpmConfigHook,
  webkitgtk_4_1,
  gtk3,
  libayatana-appindicator,
  librsvg,
  dbus,
  openssl,
  glib,
  glib-networking,
  gst_all_1,
  libpulseaudio,
  libsecret,
  makeBinaryWrapper,
  networkmanager,
  pulseaudio,
  zenity,
  android-tools,
  wl-clipboard,
  pipewire,
  customSrc ? null,
}:
let
  cleanSrc =
    if customSrc != null then
      customSrc
    else
      lib.cleanSourceWith {
        src = lib.cleanSource ../.;
        filter =
          path: type:
          let
            baseName = baseNameOf path;
          in
          !(
            type == "directory"
            && (
              baseName == ".git"
              || baseName == "target"
              || baseName == "node_modules"
              || baseName == "android"
              || baseName == ".agents"
              || baseName == ".gemini"
              || baseName == ".direnv"
            )
          );
      };
in
rustPlatform.buildRustPackage rec {
  pname = "vortex";
  version = "1.0.0-beta.6";

  src = cleanSrc;

  pnpmRoot = "linux/ui-tauri";

  pnpmDeps = fetchPnpmDeps {
    inherit pname version pnpm;
    src = cleanSrc;
    sourceRoot = "${cleanSrc.name}/linux/ui-tauri";
    fetcherVersion = 4;
    hash = "sha256-LE4Yv6Hm/sv4T5DM6Z6V+R29NjjrlJEQfiEbFU7CcNc=";
  };

  cargoRoot = "linux/ui-tauri/src-tauri";
  buildAndTestSubdir = "linux/ui-tauri/src-tauri";

  cargoLock = {
    lockFile = ../linux/ui-tauri/src-tauri/Cargo.lock;
  };

  postPatch = lib.optionalString stdenv.hostPlatform.isLinux ''
    substituteInPlace $cargoDepsCopy/libappindicator-sys-*/src/lib.rs \
      --replace-fail "libayatana-appindicator3.so.1" "${libayatana-appindicator}/lib/libayatana-appindicator3.so.1"
  '';

  nativeBuildInputs = [
    pkg-config
    cargo-tauri.hook
    rustPlatform.cargoSetupHook
    nodejs
    pnpm
    pnpmConfigHook
    wrapGAppsHook3
    makeBinaryWrapper
  ];

  buildInputs = [
    webkitgtk_4_1
    gtk3
    libayatana-appindicator
    librsvg
    dbus
    openssl
    glib
    glib-networking
    gst_all_1.gstreamer
    gst_all_1.gst-plugins-base
    gst_all_1.gst-plugins-good
    gst_all_1.gst-plugins-bad
    gst_all_1.gst-libav
    pipewire
    libpulseaudio
    libsecret
  ];

  tauriBuildFlags = [ "--no-bundle" ];
  dontTauriInstall = true;

  installPhase = ''
    runHook preInstall

    mkdir -p $out/bin $out/share/applications $out/share/icons/hicolor/512x512/apps $out/lib/systemd/user

    # Copy release binary from cargo target directory
    if [ -f target/release/vortex-ui-tauri ]; then
      install -Dm755 target/release/vortex-ui-tauri $out/bin/vortex-ui-tauri
    else
      install -Dm755 target/*/release/vortex-ui-tauri $out/bin/vortex-ui-tauri
    fi
    ln -sf $out/bin/vortex-ui-tauri $out/bin/vortex

    # App menu desktop launcher
    cat > $out/share/applications/vortex-ui-tauri.desktop <<EOF
[Desktop Entry]
Type=Application
Name=Vortex
Comment=Phone companion - calls, messages, notifications and earbuds hand-off
Exec=vortex-ui-tauri
Icon=vortex-ui-tauri
Terminal=false
Categories=Utility;Network;
StartupNotify=false
StartupWMClass=vortex-ui-tauri
EOF

    # Brand logo
    if [ -f linux/ui-tauri/src-tauri/icons/icon.png ]; then
      install -Dm644 linux/ui-tauri/src-tauri/icons/icon.png $out/share/icons/hicolor/512x512/apps/vortex-ui-tauri.png
    fi

    # Systemd user service
    if [ -f linux/packaging/vortex.service ]; then
      substitute linux/packaging/vortex.service $out/lib/systemd/user/vortex.service \
        --replace-fail "/usr/bin/vortex-ui-tauri" "$out/bin/vortex-ui-tauri"
    fi

    # File manager right-click extensions
    if [ -f linux/packaging/nautilus/vortex.py ]; then
      install -Dm644 linux/packaging/nautilus/vortex.py $out/share/nautilus-python/extensions/vortex.py
    fi

    runHook postInstall
  '';

  preFixup = ''
    gappsWrapperArgs+=(
      --prefix PATH : ${
        lib.makeBinPath [
          networkmanager
          pulseaudio
          zenity
          android-tools
          wl-clipboard
        ]
      }
      --prefix GST_PLUGIN_SYSTEM_PATH_1_0 : "${
        lib.makeSearchPath "lib/gstreamer-1.0" [
          gst_all_1.gstreamer
          gst_all_1.gst-plugins-base
          gst_all_1.gst-plugins-good
          gst_all_1.gst-plugins-bad
          gst_all_1.gst-libav
          pipewire
        ]
      }"
      --set-default WEBKIT_DISABLE_DMABUF_RENDERER 1
      --set-default GDK_BACKEND wayland
      --set-default XDG_DOWNLOAD_DIR "$HOME/_inbox"
    )
  '';

  meta = {
    description = "Phone companion - calls, messages, notifications and earbuds hand-off for Linux and Android";
    homepage = "https://github.com/hydrafog/vortex";
    license = lib.licenses.gpl3Plus;
    platforms = lib.platforms.linux;
    mainProgram = "vortex-ui-tauri";
  };
}
