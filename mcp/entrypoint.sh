#!/bin/sh
set -e

PLUGIN_PORT=${PLUGIN_PORT:-4400}

cleanup() {
    echo "Shutting down..."
    kill "$MCP_PID" "$PLUGIN_PID" 2>/dev/null || true
    wait "$MCP_PID" "$PLUGIN_PID" 2>/dev/null || true
    exit 0
}
trap cleanup SIGTERM SIGINT

# Start MCP server (ports 4401 + 4402 via env vars)
cd /app
node packages/server/dist/index.js &
MCP_PID=$!

# Serve plugin static files (manifest.json, plugin.js, index.html)
serve -s /plugin-dist -l "$PLUGIN_PORT" --no-clipboard &
PLUGIN_PID=$!

echo "MCP server started (SSE: ${PENPOT_MCP_SERVER_PORT:-4401}, WS: ${PENPOT_MCP_WEBSOCKET_PORT:-4402})"
echo "Plugin UI: http://0.0.0.0:${PLUGIN_PORT}"

wait $MCP_PID $PLUGIN_PID
