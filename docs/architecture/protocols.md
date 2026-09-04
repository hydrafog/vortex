# Wire & Encryption Protocols

Vortex relies on strict protocol specifications to guarantee security, cross-language interoperability, and binary compatibility between Rust and Kotlin.

## 1. Protobuf Contract
All messages exchanged over the wire are serialized using Protocol Buffers version 3.
The canonical definitions reside in `shared/proto/`:
- `vortex.proto`: Envelope definitions, command types, and payload wrappers.
- `pairing.proto`: Cryptographic parameters and SAS exchange.
- `input.proto`: Pointer moves, button presses, touch events, and key events for Universal Control.
- `file.proto`: File transfer manifests, chunk headers, and checksums.

The schema is enforced in CI (`proto-syntax` in `lefthook.yml`) to guarantee zero contract divergence between Rust and Android.

## 2. Transport Security
- **Handshake**: Noise Protocol framework with `25519` key exchange.
- **Payload Encryption**: ChaCha20-Poly1305 authenticated encryption with unique per-message nonces.
- **Integrity**: MAC tags verified before parsing any inner protobuf frame.

## 3. Physical Transports
- **Control Channel (BLE)**: Used for discovery, proximity estimation, initial handshake, small metadata packets, and session maintenance.
- **Data Channel (Wi-Fi Direct / Local TCP)**: Spun up on demand for high-throughput workloads (clipboard image payload, file drops, and H.264 video streams).
