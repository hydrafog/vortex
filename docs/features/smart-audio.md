# Smart audio handoff

Playback pauses on the laptop when a call takes the earbuds, and resumes when the call ends.

## During a call

- Vortex watches audio sinks through PipeWire or PulseAudio and BlueZ D-Bus. When the earbuds move to the phone for an incoming call, it sees the sink change.
- It pauses desktop players (Spotify, Firefox, mpv, VLC) with `Pause` over D-Bus through `zbus` to `org.mpris.MediaPlayer2.*`.
- When the call ends and the earbuds return to the laptop, Vortex resumes the paused player.
