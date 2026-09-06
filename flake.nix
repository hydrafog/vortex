{
  description = "Vortex development environment and packaging";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
      ...
    }:
    let
      perSystem = flake-utils.lib.eachDefaultSystem (
        system:
        let
          pkgs = import nixpkgs {
            inherit system;
            config = {
              allowUnfree = true;
              android_sdk.accept_license = true;
            };
          };

          androidComposition = pkgs.androidenv.composeAndroidPackages {
            cmdLineToolsVersion = "11.0";
            platformVersions = [ "36" ];
            buildToolsVersions = [ "35.0.0" "36.0.0" ];
            includeNDK = false;
            includeEmulator = false;
            includeSystemImages = false;
          };
          androidSdk = androidComposition.androidsdk;

          gstreamerPackages = (with pkgs.gst_all_1; [
            gstreamer
            gst-plugins-base
            gst-plugins-good
            gst-plugins-bad
            gst-plugins-ugly
            gst-libav
          ]) ++ [ pkgs.pipewire ];

          tauriLibraries = with pkgs; [
            webkitgtk_4_1
            gtk3
            libayatana-appindicator
            librsvg
            dbus
            openssl
            glib
            glib-networking
            libsecret
            libpulseaudio
          ];

          vortexPkg = pkgs.callPackage ./nix/package.nix { };
        in
        {
          packages = {
            default = vortexPkg;
            vortex = vortexPkg;
          };

          devShells.default = pkgs.mkShell {
            buildInputs =
              with pkgs;
              [
                # Rust toolchain
                cargo
                rustc
                rustfmt
                clippy
                cargo-tauri

                # Web and UI
                nodejs_22
                pnpm

                # Protobuf & C build tools
                protobuf
                pkg-config
                cmake
                gcc

                # Android and mobile tools (SDK via composeAndroidPackages;
                # platform-tools/adb ships inside the SDK, no separate package)
                androidSdk
                jdk17

                # Quality, hooks, and maintenance
                lefthook
                python3
                wl-clipboard
                bash
              ]
              ++ tauriLibraries
              ++ gstreamerPackages;

            JAVA_HOME = "${pkgs.jdk17}";
            ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
            ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";

            shellHook = ''
              # Nix provides the SDK via ANDROID_HOME above. Drop the stale
              # per-machine sdk.dir that pointed at ~/Android/Sdk so Gradle
              # uses the nix store SDK instead of a missing directory.
              if [ -f android/local.properties ]; then
                _sdk_dir="$(sed -n 's/^sdk\.dir=//p' android/local.properties | head -n1)"
                if [ -z "$_sdk_dir" ] || [ ! -d "$_sdk_dir" ]; then
                  rm -f android/local.properties
                fi
                unset _sdk_dir
              fi

              export LD_LIBRARY_PATH="${
                pkgs.lib.makeLibraryPath (tauriLibraries ++ gstreamerPackages)
              }:$LD_LIBRARY_PATH"

              export PKG_CONFIG_PATH="${
                pkgs.lib.makeSearchPathOutput "dev" "lib/pkgconfig" (tauriLibraries ++ gstreamerPackages)
              }:$PKG_CONFIG_PATH"

              export GST_PLUGIN_SYSTEM_PATH_1_0="${
                pkgs.lib.makeSearchPath "lib/gstreamer-1.0" gstreamerPackages
              }:$GST_PLUGIN_SYSTEM_PATH_1_0"

              export GSETTINGS_SCHEMA_DIR="${pkgs.gsettings-desktop-schemas}/share/gsettings-schemas/${pkgs.gsettings-desktop-schemas.name}:${pkgs.gtk3}/share/gsettings-schemas/${pkgs.gtk3.name}:$GSETTINGS_SCHEMA_DIR"

              echo "Vortex development environment loaded (Rust, Tauri, Node/pnpm, GStreamer, Protobuf, Android tools)."
            '';
          };
        }
      );
    in
    perSystem
    // {
      overlays.default = final: prev: {
        vortex = final.callPackage ./nix/package.nix { };
      };

      homeManagerModules = {
        default = import ./nix/home-manager.nix;
        vortex = import ./nix/home-manager.nix;
      };
    };
}
