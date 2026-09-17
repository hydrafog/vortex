# Smart audio handoff

Playback pauses on the laptop when a call takes the earbuds or speaker, and resumes when the call ends.

## Routing and mutual exclusion

Audio routing enforces single-device ownership across connected Bluetooth audio sinks (earbuds, headphones, and speakers):
- Only one device holds an active Bluetooth audio link at any time. When one device starts playing or answers a call, the other releases its Bluetooth connection to prevent simultaneous multipoint collisions and audio stutter.
- Vortex watches audio sinks through PipeWire or PulseAudio and BlueZ D-Bus. When the audio device moves to the phone for an incoming call, it sees the sink change.
- It pauses desktop players (Spotify, Firefox, mpv, VLC) with `Pause` over D-Bus through `zbus` to `org.mpris.MediaPlayer2.*`.
- When the call ends and the audio sink returns to the laptop, Vortex resumes the paused player.

## See also

- `docs/architecture/overview.md` describes desktop MPRIS integrations.
- `docs/getting-started/pairing.md` covers dual-mode Bluetooth audio device management.
- `docs/operations/troubleshooting.md` describes BlueZ connection diagnostics.
