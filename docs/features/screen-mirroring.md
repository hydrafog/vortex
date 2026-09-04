# Screen Mirroring & Casting

Vortex supports low-latency video streaming in both directions: mirroring your Android screen onto your Linux desktop and casting your Linux desktop display onto an Android device as a secondary monitor.

## Phone to Laptop (Receiver Pipeline)

When viewing your phone screen on your laptop:
1. **Android Screen Capture**: The Android app records the display via `MediaProjection` and hardware-encodes video frames using `MediaCodec` into H.264 NAL units.
2. **Encrypted Transport**: Frames are sealed with ChaCha20-Poly1305 and streamed over local Wi-Fi.
3. **Desktop Rendering Pipeline**:
   - The desktop client relies on GStreamer (`gstreamer-app`, `gst-plugins-good`, `gst-plugins-bad`).
   - Pipeline:
     ```
     appsrc -> h264parse -> avdec_h264 -> videoconvert -> gtksink
     ```
   - Frames render into a GTK3 window managed by Tauri, providing native window controls and sub-frame latency.

## Laptop to Phone (Virtual Display / Second Screen)

When using your phone or tablet as a secondary Linux monitor:
1. The desktop initiates a Wayland screencast session via `ashpd` talking to `org.freedesktop.portal.ScreenCast`.
2. Captured desktop buffers are encoded into H.264 using GStreamer hardware acceleration (VA-API / NVENC).
3. The video stream is transmitted to the Android device, which decodes it onto a `SurfaceView`.
