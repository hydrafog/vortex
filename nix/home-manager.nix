{
  config,
  lib,
  pkgs,
  ...
}:
let
  cfg = config.services.vortex;

  envPrefix = lib.concatStringsSep " " (
    lib.filter (s: s != "") [
      (lib.optionalString (cfg.backend != "auto") "GDK_BACKEND=${cfg.backend}")
      (lib.optionalString cfg.disableDmabuf "WEBKIT_DISABLE_DMABUF_RENDERER=1")
      (lib.optionalString (
        cfg.fileSharing.incomingDir != ""
      ) "XDG_DOWNLOAD_DIR=${cfg.fileSharing.incomingDir}")
    ]
  );

  execArgs = lib.concatStringsSep " " cfg.extraArgs;
  execCmd =
    if envPrefix != "" then
      "env ${envPrefix} ${cfg.package}/bin/vortex-ui-tauri${
        lib.optionalString (execArgs != "") " ${execArgs}"
      }"
    else
      "${cfg.package}/bin/vortex-ui-tauri${lib.optionalString (execArgs != "") " ${execArgs}"}";
in
{
  options.services.vortex = {
    enable = lib.mkEnableOption "Vortex Android-Linux bridge client";

    package = lib.mkOption {
      type = lib.types.package;
      default = pkgs.vortex or pkgs.callPackage ./package.nix { };
      description = "The Vortex package to use.";
    };

    backend = lib.mkOption {
      type = lib.types.enum [
        "wayland"
        "x11"
        "auto"
      ];
      default = "wayland";
      description = ''
        Windowing backend for GDK. Defaults to native 'wayland'.
        (Upstream install script defaulted to 'x11' solely as a workaround for a
        GNOME fractional scaling WebKitGTK title-bar hitbox bug; on Hyprland and
        modern Wayland compositors, 'wayland' runs natively).
      '';
    };

    disableDmabuf = lib.mkOption {
      type = lib.types.bool;
      default = true;
      description = "Disable WebKitGTK DMABuf renderer to avoid blank rendering on various GPU stacks.";
    };

    autostart = lib.mkOption {
      type = lib.types.bool;
      default = true;
      description = "Launch Vortex automatically on graphical login via XDG desktop autostart.";
    };

    systemdService = lib.mkOption {
      type = lib.types.bool;
      default = false;
      description = "Run Vortex as a systemd user service under graphical-session.target instead of XDG autostart.";
    };

    extraArgs = lib.mkOption {
      type = lib.types.listOf lib.types.str;
      default = [ "--hidden" ];
      description = "Extra command line flags to pass to Vortex on autostart or service launch.";
    };

    # Feature Toggles
    notifications = {
      enable = lib.mkOption {
        type = lib.types.bool;
        default = true;
        description = "Enable bidirectional notification mirror between phone and laptop.";
      };
      mirrorPhoneToLaptop = lib.mkOption {
        type = lib.types.bool;
        default = true;
        description = "Show mirrored phone notifications on the laptop.";
      };
      mirrorLaptopToPhone = lib.mkOption {
        type = lib.types.bool;
        default = true;
        description = "Forward laptop desktop notifications to the phone.";
      };
    };

    clipboardSync = {
      enable = lib.mkOption {
        type = lib.types.bool;
        default = true;
        description = "Enable universal clipboard sync (text, images, and SMS login codes) between phone and laptop.";
      };
    };

    smartAudioHandoff = {
      enable = lib.mkOption {
        type = lib.types.bool;
        default = true;
        description = "Enable smart audio handoff (earbuds follow your music between phone and laptop).";
      };
    };

    phoneCompanion = {
      enable = lib.mkOption {
        type = lib.types.bool;
        default = true;
        description = "Enable phone companion features (call banner accept/decline, SMS, contacts, and dialing).";
      };
    };

    browsingHandoff = {
      enable = lib.mkOption {
        type = lib.types.bool;
        default = true;
        description = "Enable browsing handoff (continue web pages between phone and laptop).";
      };
    };

    notesSync = {
      enable = lib.mkOption {
        type = lib.types.bool;
        default = true;
        description = "Enable notes and to-dos sync between phone and laptop.";
      };
    };

    proximityLock = {
      autoLock = lib.mkOption {
        type = lib.types.bool;
        default = false;
        description = "Automatically lock laptop when phone moves out of Bluetooth range.";
      };
      autoUnlock = lib.mkOption {
        type = lib.types.bool;
        default = false;
        description = "Automatically unlock laptop when phone comes back into Bluetooth range.";
      };
    };

    fileSharing = {
      enable = lib.mkOption {
        type = lib.types.bool;
        default = true;
        description = "Enable drop-style file sharing over BLE / Wi-Fi Direct.";
      };
      incomingDir = lib.mkOption {
        type = lib.types.str;
        default = "${config.home.homeDirectory}/_inbox";
        description = "Directory where files received from the phone are saved.";
      };
      autoAccept = lib.mkOption {
        type = lib.types.bool;
        default = false;
        description = "Auto-accept incoming files from phone without a prompt banner.";
      };
      fileManagerIntegration = lib.mkOption {
        type = lib.types.bool;
        default = true;
        description = "Install right-click 'Share via Vortex' service menus for Nautilus and Dolphin.";
      };
    };

    universalControl = {
      enable = lib.mkOption {
        type = lib.types.bool;
        default = false;
        description = "Enable experimental Universal Control (push cursor off screen edge to control phone).";
      };
      placement = lib.mkOption {
        type = lib.types.enum [
          "right"
          "left"
          "top"
          "bottom"
        ];
        default = "right";
        description = "Screen edge where the phone is positioned for Universal Control.";
      };
    };
  };

  config = lib.mkIf cfg.enable {
    home.packages = [ cfg.package ];

    # XDG desktop autostart entry (when not using systemd service)
    xdg.configFile."autostart/vortex-ui-tauri.desktop" =
      lib.mkIf (cfg.autostart && !cfg.systemdService)
        {
          text = ''
            [Desktop Entry]
            Type=Application
            Name=Vortex
            Comment=Phone companion — starts with the session
            Exec=${execCmd}
            Icon=vortex-ui-tauri
            Terminal=false
            Categories=Utility;Network;
            StartupNotify=false
            StartupWMClass=vortex-ui-tauri
            X-GNOME-Autostart-enabled=true
            X-GNOME-Autostart-Delay=5
          '';
        };

    # Systemd user service unit (alternative session management)
    systemd.user.services.vortex = lib.mkIf cfg.systemdService {
      Unit = {
        Description = "Vortex phone companion";
        After = [ "graphical-session.target" ];
        PartOf = [ "graphical-session.target" ];
      };
      Service = {
        ExecStart = execCmd;
        Restart = "on-failure";
        RestartSec = 5;
      };
      Install = {
        WantedBy = [ "graphical-session.target" ];
      };
    };

    # Declarative settings persistence
    xdg.configFile."vortex/smart_switch.json".text = builtins.toJSON {
      enabled = cfg.smartAudioHandoff.enable;
      changed_at = 0;
    };

    xdg.configFile."vortex/proximity.json".text = builtins.toJSON {
      auto_lock = cfg.proximityLock.autoLock;
      auto_unlock = cfg.proximityLock.autoUnlock;
    };

    xdg.dataFile = {
      "vortex/file_auto_accept".text = if cfg.fileSharing.autoAccept then "1\n" else "0\n";

      "vortex/universal_control/placement".text = "${cfg.universalControl.placement}\n";
    }
    // (lib.optionalAttrs cfg.fileSharing.fileManagerIntegration {
      "kio/servicemenus/vortex-share.desktop" = {
        text = ''
          [Desktop Entry]
          Type=Service
          MimeType=all/all;
          Actions=vortexShare;
          X-KDE-Priority=TopLevel

          [Desktop Action vortexShare]
          Name=Share via Vortex
          Icon=vortex-ui-tauri
          Exec=${cfg.package}/bin/vortex-ui-tauri --share %F
        '';
        executable = true;
      };

      "kservices5/ServiceMenus/vortex-share.desktop" = {
        text = ''
          [Desktop Entry]
          Type=Service
          MimeType=all/all;
          Actions=vortexShare;
          X-KDE-Priority=TopLevel

          [Desktop Action vortexShare]
          Name=Share via Vortex
          Icon=vortex-ui-tauri
          Exec=${cfg.package}/bin/vortex-ui-tauri --share %F
        '';
        executable = true;
      };
    });
  };
}
