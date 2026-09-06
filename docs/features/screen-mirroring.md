# Screen mirroring and casting

Video goes both ways. The phone screen can appear on the laptop, and the laptop desktop can extend onto the phone.

## Phone to laptop

To show the phone on the laptop:
1. The Android app captures the display with `MediaProjection` and encodes with `MediaCodec` to H.264 NAL units.
2. Each frame is encrypted with ChaCha20-Poly1305 and sent over local Wi-Fi.
3. The desktop decodes with GStreamer (`gstreamer-app`, `gst-plugins-good`, `gst-plugins-bad`) through this pipeline:
   ```
   appsrc -> h264parse -> avdec_h264 -> videoconvert -> gtksink
   ```
   Frames appear in a GTK3 window owned by Tauri. Window controls stay native.

## Laptop to phone

To use the phone as a second Linux monitor:
1. The desktop opens a Wayland screencast through `ashpd` and `org.freedesktop.portal.ScreenCast`.
2. GStreamer encodes captured buffers to H.264, with VA-API or NVENC when present.
3. Android decodes the stream onto a `SurfaceView`.
