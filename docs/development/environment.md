# Development environment and standards

Nix pins the toolchain. Direnv loads it. Lefthook runs checks before commit.

## Toolchain and shell

To enter the shell:
```bash
direnv allow
# or
nix develop
```

The shell includes Rust 1.77 or later with `cargo`, `rustc`, `rustfmt`, `clippy`, and `cargo-tauri`. It also includes Node.js 22 with `pnpm`, `protoc`, GTK3, WebKitGTK 4.1, GStreamer, D-Bus headers, Python 3 utilities, and `adb`.

## Pre-commit hooks and Lefthook

Hooks live in `lefthook.yml`. To install them:
```bash
lefthook install
```

On commit, Lefthook runs:
1. Guard for merge markers, possible hardcoded secrets, and files over 5 MB.
2. Line limit check at 600 production lines per file (`scripts/maintenance/check-line-limit`).
3. Format and syntax checks with `cargo fmt` for Rust, `vue-tsc -b` for the frontend, `bash -n` for shell, `protoc` for `shared/proto`, and a JSON parse for config files.
4. Commit message check for a lowercase prefix (`feat`, `fix`, `chore`, `docs`, `refactor`) and a subject within 72 characters (`scripts/hooks/commit-msg`).

On push, it runs `cargo clippy --locked --all-targets -- -D warnings`, `cargo test --locked`, the frontend build (`pnpm run build`), and Android unit plus crypto parity tests (`shared/vectors`).
