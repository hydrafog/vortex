# Shared Cross-Platform Specifications & Contracts

The `shared/` directory contains source-of-truth contracts and test specifications shared between the Linux desktop and Android mobile implementations.

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

### Protocol Buffers (`shared/proto/`)

`vortex.proto` is the wire contract governing all point-to-point communication. Rust compiles it through `prost-build` in build scripts (`linux/daemon/build.rs`). Android compiles it through `protobuf-gradle-plugin` into Kotlin/Java models during Gradle build. Lefthook validates `.proto` syntax on commit (`proto-syntax`).

### Crypto Test Vectors (`shared/vectors/`)

Deterministic test vectors in JSON format test handshake transitions (`Noise_XX`, `Noise_IK`), HKDF key derivation, ChaCha20-Poly1305 encryption, and SAS emoji generation. The `tests/parity-test` suite replays them to guarantee byte-level parity between Rust's `snow`/`ring` and Android's `Noise-Java`/Keystore implementations.
