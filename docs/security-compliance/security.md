# Security and compliance

Traffic stays on the local network and stays encrypted. There is no cloud account and no relay.

## Threat model and principles

1. The laptop and phone talk point to point over local Bluetooth and Wi-Fi. There is no telemetry, no relay, and no login.
2. Both sides run `Noise_XX_25519_ChaChaPoly_BLAKE2s`. It blocks eavesdropping, tampering, and replay. Peer public keys sit encrypted in the OS keyring (`libsecret` through `secret-service` on Linux).
3. Pairing shows a three emoji fingerprint on both screens. Confirm only when the two match, which blocks a person in the middle.
4. `vortex_inject` runs as the non-root `shell` UID. It touches `/dev/uinput` only and exits when the session ends.
5. When the clipboard carries `x-kde-passwordManagerHint` or a password MIME type, Vortex skips that item.
