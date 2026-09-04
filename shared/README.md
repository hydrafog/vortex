# Shared Cross-Platform Specifications & Contracts

The `shared/` directory contains source-of-truth contracts and test specifications shared between the **Linux desktop** (`linux/`) and **Android mobile** (`android/`) implementations.

## Directory Structure

```
shared/
├── proto/               # Canonical Protocol Buffer definitions
│   ├── vortex.proto     # Wire format, commands, and streaming envelopes
│   └── README.md        # Code generation instructions (Rust & Kotlin)
└── vectors/             # Deterministic cryptographic test vectors
    ├── v1/              # Version 1 vector suite (Noise XX/IK, ChaCha20, SAS)
    └── README.md        # Parity testing guide
```

## Subsystems

### 1. Protocol Buffers (`shared/proto/`)
- Contains `vortex.proto`, the wire contract governing all point-to-point communication.
- **Rust integration**: Automatically compiled via `prost-build` in build scripts (`linux/daemon/build.rs`).
- **Android integration**: Compiled via `protobuf-gradle-plugin` into Kotlin/Java models during Gradle build.
- **CI / Hook Guard**: Lefthook validates `.proto` syntax on commit (`proto-syntax`).

### 2. Crypto Test Vectors (`shared/vectors/`)
- Houses deterministic test vectors in JSON format.
- Tests handshake transitions (`Noise_XX`, `Noise_IK`), HKDF key derivation, ChaCha20-Poly1305 encryption, and SAS emoji generation.
- Replayed by `tests/parity-test` to guarantee byte-level parity between Rust's `snow`/`ring` and Android's `Noise-Java`/Keystore implementations.
