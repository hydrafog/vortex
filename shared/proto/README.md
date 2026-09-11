# shared/proto

This folder contains the cross-language Protocol Buffer definitions used by Vortex. The schemas define wire message structures, payload envelopes, transport-safe typed messages, and compatibility boundaries between Rust and Kotlin codebases. The main file is `vortex.proto`.

`shared/proto` is the contract layer between platforms. Schema changes are protocol changes, not code changes. Careless changes can cause Android and Linux to drift, make decode failures hard to debug, and silently break backward compatibility.

## Rust generation

Rust types are generated through `prost-build`, usually invoked from `build.rs`. Generated types should not be edited by hand.

```bash
cargo build
```

## Kotlin generation

Kotlin types are generated through `protobuf-gradle-plugin`, integrated into the Gradle build graph. Generated outputs remain derived artifacts.

```bash
./gradlew generateProto
```

## Field numbering

Field numbers are permanent protocol real estate. Fields `1-15` reserve the most frequently used fields because they encode in one byte; `16+` are for less frequent fields. Removed fields are marked as `reserved` and their numbers are never reused.

```proto
message Example {
  reserved 4;
}
```

## Versioning

Safe changes add optional fields, add new messages, or add new enum values with safe defaults. Breaking changes change field types, reuse field numbers, or change existing semantics without version gating.

## Compatibility

Older peers ignore unknown optional fields. Newer peers tolerate missing optional fields. Message wrappers remain stable once experiments converge. Room is reserved for new payload types, and every schema-affecting decision is documented in protocol review.

## Testing

Tests include round-trip encode/decode, Rust-to-Kotlin decode, Kotlin-to-Rust decode, and malformed payload handling. Test cases cover empty optional fields, populated nested messages, and unknown field preservation.

## See also

- `docs/architecture/protocols.md` details the wire protocol and encryption.
- `shared/README.md` describes the shared directory structure and subsystems.
