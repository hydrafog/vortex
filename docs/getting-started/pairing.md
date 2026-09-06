# Pairing and security

Pairing links the laptop and the phone directly. No cloud account is involved.

## Pairing workflow

1. Discovery. Both devices advertise and scan over Bluetooth Low Energy. When each sees the other, the handshake starts.
2. Handshake. Both sides run Noise XX (`Noise_XX_25519_ChaChaPoly_BLAKE2s`). The resulting ephemeral keys give forward secrecy.
3. Short authentication string. Both screens show the same three emoji, for example `⚡ 🦊 🚀`. Compare them and press Confirm on the desktop when they match.
4. Persistence. After a match, both sides store the peer public key in the system keyring (`libsecret` on Linux, Android Keystore on Android). Later connections authenticate without another emoji check.

## Dual-mode Bluetooth devices

BlueZ ties a bonded classic audio device to the BR/EDR bearer. A phone already paired as a laptop speaker or headset keeps that bearer, and BlueZ then refuses GATT over BLE for Vortex.

For a phone already paired for classic audio:
1. Remove the classic entry from BlueZ:
   ```bash
   bluetoothctl remove <device_mac_address>
   ```
2. Pair again inside Vortex over BLE.
3. Confirm the three emoji.

After that, Vortex encrypts over BLE and local Wi-Fi and moves audio through smart audio handoff.
