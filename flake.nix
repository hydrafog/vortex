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
            };
          };

          gstreamerPackages = with pkgs.gst_all_1; [
            gstreamer
            gst-plugins-base
            gst-plugins-good
            gst-plugins-bad
            gst-libav
          ];

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

                # Android and mobile tools
                android-tools
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

            shellHook = ''
              export LD_LIBRARY_PATH="${
                pkgs.lib.makeLibraryPath (tauriLibraries ++ gstreamerPackages)
              }:$LD_LIBRARY_PATH"

              export PKG_CONFIG_PATH="${
                pkgs.lib.makeSearchPathOutput "dev" "lib/pkgconfig" (tauriLibraries ++ gstreamerPackages)
              }:$PKG_CONFIG_PATH"

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
