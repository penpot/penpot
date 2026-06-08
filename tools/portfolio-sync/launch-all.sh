#!/usr/bin/env bash
# launch-all.sh — Start the portfolio↔Penpot sync stack.
#
# Targets:
#   penpot       — bring up Penpot Docker (delegates to penpot-launch.sh)
#   portfolio    — start the portfolio dev server (Astro)
#   bridge       — start penpot-bridge (headless Playwright keeps MCP REPL alive)
#   watcher      — start portfolio-watcher (re-runs pipeline on file changes)
#   webhook      — start webhook-server (Vercel + GitHub push triggers)
#   screenshots  — one-shot run of the screenshot pipeline
#   sync         — bridge + watcher + webhook (all background services)
#   stop-sync    — kill the three background services started by 'sync'
#   all          — penpot
#
# Usage:
#   ./launch-all.sh [target]

set -euo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG="$SCRIPTS_DIR/portfolio-sync.config.json"

# Read portfolio_dir from config (falls back if config is missing)
read_config_key() {
  local key="$1"
  node -e "try { const c = require('$CONFIG'); console.log(c['$key'] || ''); } catch { process.exit(0); }" 2>/dev/null
}

start_penpot() {
  echo ""
  echo "━━━ Penpot (port 9001) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  bash "$SCRIPTS_DIR/penpot-launch.sh"
}

run_screenshots() {
  echo ""
  echo "━━━ Portfolio → Penpot Screenshot Pipeline ━━━━━━━━━━"
  node "$SCRIPTS_DIR/screenshot-pipeline.mjs" "${@:2}"
}

start_portfolio() {
  echo ""
  echo "━━━ Portfolio Dev Server (background) ━━━━━━━━━━━━━━"
  local portfolio_dir
  portfolio_dir="$(read_config_key portfolio_dir)"
  if [ -z "$portfolio_dir" ] || [ ! -d "$portfolio_dir" ]; then
    echo "  Error: portfolio_dir not set or does not exist in $CONFIG"
    exit 1
  fi
  (cd "$portfolio_dir" && npm run dev >> /tmp/portfolio-dev.log 2>&1) &
  echo $! > /tmp/portfolio-dev.pid
  echo "  Started PID $(cat /tmp/portfolio-dev.pid) — log: /tmp/portfolio-dev.log"
}

TARGET="${1:-all}"

case "$TARGET" in
  penpot)       start_penpot ;;
  screenshots)  run_screenshots "$@" ;;
  bridge)
    echo ""
    echo "━━━ Penpot Bridge (background) ━━━━━━━━━━━━━━━━━━━━━"
    node "$SCRIPTS_DIR/penpot-bridge.mjs" >> /tmp/penpot-bridge.log 2>&1 &
    echo $! > /tmp/penpot-bridge.pid
    echo "  Started PID $(cat /tmp/penpot-bridge.pid) — log: /tmp/penpot-bridge.log"
    ;;
  watcher)
    echo ""
    echo "━━━ Portfolio Watcher (background) ━━━━━━━━━━━━━━━━━"
    node "$SCRIPTS_DIR/portfolio-watcher.mjs" >> /tmp/portfolio-watcher.log 2>&1 &
    echo $! > /tmp/portfolio-watcher.pid
    echo "  Started PID $(cat /tmp/portfolio-watcher.pid) — log: /tmp/portfolio-watcher.log"
    ;;
  webhook)
    echo ""
    echo "━━━ Webhook Server (background) ━━━━━━━━━━━━━━━━━━━━"
    node "$SCRIPTS_DIR/webhook-server.mjs" >> /tmp/webhook-server.log 2>&1 &
    echo $! > /tmp/webhook-server.pid
    echo "  Started PID $(cat /tmp/webhook-server.pid) — log: /tmp/webhook-server.log"
    ;;
  sync)
    "$0" bridge
    "$0" watcher
    "$0" webhook
    ;;
  stop-sync)
    echo ""
    echo "━━━ Stopping sync services ━━━━━━━━━━━━━━━━━━━━━━━━━"
    for pidfile in /tmp/penpot-bridge.pid /tmp/portfolio-watcher.pid /tmp/webhook-server.pid; do
      if [ -f "$pidfile" ]; then
        pid=$(cat "$pidfile")
        if kill "$pid" 2>/dev/null; then
          echo "  Killed PID $pid ($pidfile)"
        else
          echo "  PID $pid not running ($pidfile)"
        fi
        rm -f "$pidfile"
      else
        echo "  No PID file: $pidfile"
      fi
    done
    ;;
  portfolio)    start_portfolio ;;
  all)          start_penpot ;;
  *)
    echo "Unknown target: $TARGET"
    echo "Usage: $0 [penpot|portfolio|bridge|watcher|webhook|sync|stop-sync|screenshots|all]"
    exit 1
    ;;
esac

echo ""
echo "━━━ Status ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  Penpot         → http://localhost:9001"
echo "  Portfolio      → http://localhost:4321"
echo "  Bridge status  → http://localhost:9002/"
echo "  Webhook        → http://localhost:9090/status"
echo "  Tunnel (opt)   → cloudflared tunnel --url http://localhost:9090"
echo ""
