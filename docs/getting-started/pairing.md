# Pairing & Security

Vortex uses modern, zero-cloud peer authentication inspired by Signal and Apple Continuity.

## Pairing Workflow

1. **Discovery**:
   - Both devices listen and advertise over Bluetooth Low Energy (BLE).
   - Once discovered, a secure session handshake initiates.

2. **Handshake**:
   - The devices execute a Noise Protocol framework handshake (`Noise_XX_25519_ChaChaPoly_BLAKE2s`).
   - Ephemeral keys are established to ensure forward secrecy.

3. **Short Authentication String (SAS)**:
   - To defeat man-in-the-middle (MITM) attacks, a derived 3-emoji Short Authentication String (SAS) is displayed simultaneously on both the laptop and phone screens.
   - Example: `⚡ 🦊 🚀`
   - Compare the three emojis on both devices and click **Confirm** on the desktop.

4. **Persistence**:
   - Successful pairing stores the mutual public keys in the system keyring (`libsecret` on Linux, Android Keystore on Android).
   - Reconnections are authenticated automatically without repeating SAS confirmation.
