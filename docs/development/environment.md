# Development Environment & Standards

This project uses modern Nix, Direnv, and Lefthook workflows to maintain strict code hygiene, reproducible toolchains, and fast feedback loops.

## Toolchain & Shell

Enter the development environment:
```bash
direnv allow
# or
nix develop
```

The shell provides:
- Rust (>= 1.77) with `cargo`, `rustc`, `rustfmt`, `clippy`, and `cargo-tauri`.
- Node.js 22 with `pnpm`.
- Protocol Buffers compiler (`protoc`).
- System GTK3, WebKitGTK 4.1, GStreamer, and D-Bus headers.
- Python 3 with maintenance utilities.
- Android platform tools (`adb`).

## Pre-Commit Hooks & Lefthook

Hooks are managed via `lefthook.yml`. To install them into your local Git repository:
```bash
lefthook install
```

### Checks Enforced on Commit:
1. **Pre-commit Guard**: Scans for accidental merge conflict markers, potential hardcoded secrets, and files over 5 MB.
2. **Line Limit Check**: Enforces a 600 production-line limit per file (`scripts/maintenance/check-line-limit`) to preserve component modularity.
3. **Format & Syntax**:
   - `cargo fmt` on Rust crates
   - `vue-tsc -b` on frontend code
   - `bash -n` on shell scripts
   - `protoc` syntax validation on `shared/proto`
   - JSON parsing check on configuration files
4. **Conventional Commits**: Enforces lowercase prefix format (`feat`, `fix`, `chore`, `docs`, `refactor`, etc.) and a 72-character subject limit via `scripts/hooks/commit-msg`.

### Checks Enforced on Push:
- `cargo clippy --locked --all-targets -- -D warnings`
- `cargo test --locked`
- Frontend build (`pnpm run build`)
- Android unit tests and crypto parity tests (`shared/vectors`)
