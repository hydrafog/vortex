# Smart audio handoff

Playback pauses on the laptop when a call takes the earbuds, and resumes when the call ends.

## During a call

Audio routing responds to call status across connected Bluetooth audio sinks:
- Vortex watches audio sinks through PipeWire or PulseAudio and BlueZ D-Bus. When the earbuds move to the phone for an incoming call, it sees the sink change.
- It pauses desktop players (Spotify, Firefox, mpv, VLC) with `Pause` over D-Bus through `zbus` to `org.mpris.MediaPlayer2.*`.
- When the call ends and the earbuds return to the laptop, Vortex resumes the paused player.

## See also

- `docs/architecture/overview.md` describes desktop MPRIS integrations.
- `docs/getting-started/pairing.md` covers dual-mode Bluetooth audio device management.
- `docs/operations/troubleshooting.md` describes BlueZ connection diagnostics.
