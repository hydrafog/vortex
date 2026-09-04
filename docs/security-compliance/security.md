# Security & Compliance

Vortex operates on a zero-trust, local-first security model with strict cryptographic assurances.

## Threat Model & Principles

1. **No Cloud Infrastructure**:
   - Zero telemetric pings, zero relay servers, zero account logins.
   - All network traffic is strictly point-to-point over local Bluetooth and Wi-Fi networks.

2. **Noise Protocol Handshake**:
   - Implements `Noise_XX_25519_ChaChaPoly_BLAKE2s`.
   - Protects against active network eavesdropping, tampering, and replay attacks.
   - Mutual public keys are stored encrypted in the OS keyring (`libsecret` via `secret-service` on Linux).

3. **Short Authentication String (SAS)**:
   - Derives a visually comparable 3-emoji fingerprint during pairing to prevent active MITM attacks.

4. **Input Injection Sandbox**:
   - The Android helper `vortex_inject` operates under the non-root `shell` UID.
   - It only accesses `/dev/uinput` for input events and terminates cleanly when the session ends.

5. **Clipboard Secret Isolation**:
   - Password managers providing `x-kde-passwordManagerHint` or password MIME markers are excluded from network synchronization.
