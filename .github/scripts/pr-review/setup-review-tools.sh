#!/usr/bin/env bash
set -euo pipefail

scripts=$(cd -- "$(dirname -- "$0")" && pwd)
review="$RUNNER_TEMP/review"
mkdir -p "$review"
python3 "$scripts/render-mcp-config.py" "${PROVIDER,,}" > "$review/mcp.json"

# Keep the CLI outside the checkout. Its own Playwright dependency installs
# the matching browser and Linux libraries inside the reviewer's container.
tools="$review/playwright"
npm install --prefix "$tools" --no-audit --no-fund --ignore-scripts @playwright/cli@0.1.25
"$tools/node_modules/.bin/playwright" install --with-deps chromium
echo "$tools/node_modules/.bin" >> "$GITHUB_PATH"
echo "PLAYWRIGHT_MCP_OUTPUT_DIR=$review/playwright-output" >> "$GITHUB_ENV"
