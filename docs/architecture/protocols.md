# Wire and encryption protocols

Vortex pins the exact bytes Rust and Kotlin exchange, so a message built on one side parses on the other.

## Protobuf contract
All wire messages use Protocol Buffers version 3. The canonical files live in `shared/proto/`:
- `vortex.proto` holds envelopes, command types, and payload wrappers.
- `pairing.proto` holds cryptographic parameters and SAS exchange.
- `input.proto` holds pointer moves, button presses, touch events, and key events for Universal Control.
- `file.proto` holds transfer manifests, chunk headers, and checksums.

CI checks the schema (`proto-syntax` in `lefthook.yml`) to keep Rust and Android in agreement.

## Transport security
- Handshake runs Noise XX with 25519 key exchange.
- Payloads use ChaCha20-Poly1305 with a fresh nonce per message.
- The receiver checks the MAC tag before it parses the inner protobuf frame.

## Physical transports
- BLE carries discovery, proximity estimates, handshake, small metadata packets, and session keepalive.
- Wi-Fi Direct or local TCP starts on demand for clipboard images, file drops, and H.264 video.
