#!/usr/bin/env bash
set -euo pipefail

PUBLIC_REMOTE=${PUBLIC_REMOTE:-git@github.com:zoir-dev/vortex.git}
TAG=${1:-}

SRC=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && git rev-parse --show-toplevel)
SRC_HEAD=$(git -C "$SRC" rev-parse --short HEAD)
if [[ -n $(git -C "$SRC" status --porcelain) ]]; then
  echo "✗ working tree is dirty — commit first (the snapshot mirrors HEAD)" >&2
  exit 1
fi
[[ -f "$SRC/README.en.md" ]] || { echo "✗ README.en.md missing" >&2; exit 1; }

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

echo "→ fetching public repo…"
if git clone --depth 1 "$PUBLIC_REMOTE" "$TMP/pub" 2>/dev/null; then
  :
else
  echo "  (empty/new public repo — first publish)"
  git -C "$TMP" init -q -b main pub
  git -C "$TMP/pub" remote add origin "$PUBLIC_REMOTE"
fi

find "$TMP/pub" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
git -C "$SRC" archive HEAD | tar -x -C "$TMP/pub"
while IFS= read -r pat; do
  [[ -z "$pat" || "$pat" == \#* ]] && continue
  if [[ "$pat" == *"*"* ]]; then
    find "$TMP/pub" -name "$pat" -prune -exec rm -rf {} + 2>/dev/null || true
  else
    rm -rf "${TMP:?}/pub/${pat}" 2>/dev/null || true
    find "$TMP/pub" -mindepth 2 -type d -name "$pat" -prune -exec rm -rf {} + 2>/dev/null || true
  fi
done < "$SRC/scripts/publish-exclude.txt"

mv "$TMP/pub/README.en.md" "$TMP/pub/README.md"

cd "$TMP/pub"
git add -A
if git diff --cached --quiet; then
  echo "✓ public repo already up to date — nothing to publish"
else
  MSG="release: snapshot ${TAG:-$(date +%Y-%m-%d)} (dev @${SRC_HEAD})"
  git commit -q -m "$MSG"
  git push origin main
  echo "✓ pushed: $MSG"
fi

if [[ -n "$TAG" ]]; then
  git tag -f "$TAG"
  git push -f origin "$TAG"
  echo "✓ tagged $TAG → the public repo's release workflow builds the signed APK"
fi
