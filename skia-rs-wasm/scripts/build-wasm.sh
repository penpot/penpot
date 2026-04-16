#!/usr/bin/env bash
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Pass TILE_SCHEDULER=1 to build with the new dependency-aware tile renderer.
# Usage: TILE_SCHEDULER=1 ./scripts/build-wasm.sh

echo "[build:wasm] Building render-wasm (WASM + JS glue) via Docker..."
if ! docker run --rm \
  -e NODE_ENV=production \
  -e CI=true \
  -e TILE_SCHEDULER="${TILE_SCHEDULER:-}" \
  -v "$REPO_ROOT:/home/penpot/penpot:z" \
  -w /home/penpot/penpot/render-wasm \
  penpotapp/devenv:latest \
  sudo -EH -u penpot ./build; then
  echo "[build:wasm] ERROR: render-wasm Docker build failed." >&2
  echo "[build:wasm] Run: docker pull penpotapp/devenv:latest" >&2
  exit 1
fi
echo "[build:wasm] Done. Artifacts in public/wasm/"
