# Security and compliance

Traffic stays on the local network and stays encrypted.

## Threat model and principles

The security model prioritizes local communication and cryptographic verification across five principles:

1. The laptop and phone talk point to point over local Bluetooth and Wi-Fi. There is no telemetry, no relay, and no login.
2. Both sides run `Noise_XX_25519_ChaChaPoly_BLAKE2s`. It blocks eavesdropping, tampering, and replay. Peer public keys sit encrypted in the OS keyring (`libsecret` through `secret-service` on Linux).
3. Pairing shows a three emoji fingerprint on both screens. Confirm only when the two match, which blocks a person in the middle.
4. `vortex_inject` runs as the non-root `shell` UID. It touches `/dev/uinput` only and exits when the session ends.
5. When the clipboard carries `x-kde-passwordManagerHint` or a password MIME type, Vortex skips that item.

## See also

- `docs/architecture/protocols.md` describes cryptographic contracts and ChaCha20 framing.
- `docs/getting-started/pairing.md` covers out-of-band SAS verification.
- `docs/features/clipboard-sync.md` describes password manager hint filtering.
