# Smart Audio Handoff

Smart Audio Handoff coordinates media playback and phone call state across your devices.

## How It Works

1. **Bluetooth Audio Tracking**:
   - Vortex monitors connected audio sink devices via PipeWire / PulseAudio and BlueZ D-Bus APIs.
   - When wireless earbuds switch connection to your phone for an incoming phone call, Vortex detects the sink transition.

2. **MPRIS Media Orchestration**:
   - Through direct D-Bus calls via `zbus` to `org.mpris.MediaPlayer2.*`, Vortex automatically issues `Pause` commands to active desktop media players (Spotify, Firefox, mpv, VLC).

3. **Call Resolution & Resume**:
   - When the phone call ends and your earbuds reconnect to the laptop, Vortex automatically restores playback on your active media player.
