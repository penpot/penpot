#!/usr/bin/env bash
#
# Penpot Performance Tests
#
# k6-based load/performance test suite for the Penpot backend.
#
# Prerequisites:
#   - k6 (https://k6.io/) installed and in PATH
#   - A running Penpot backend (local devenv or remote)
#
# Usage:
#   ./run.sh smoke                          # 1 VU, 1 iteration smoke test
#   ./run.sh lifecycle                      # 1 VU, 1 iteration (defaults)
#   ./run.sh lifecycle -v 10 -n 5           # 10 VUs, 5 iterations each
#   ./run.sh lifecycle -u http://remote:6060 -m register -v 5
#   ./run.sh clean                          # Remove test results

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------

BASE_URL="${PENPOT_BASE_URL:-http://localhost:6060}"
VUS=1
ITER=1
REGISTER_MODE="${PENPOT_REGISTER_MODE:-demo}"
K6="${K6:-k6}"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

usage() {
  cat <<EOF
Penpot Performance Tests

Usage:
  $(basename "$0") <command> [options]

Commands:
  smoke       1 VU, 1 iteration smoke test (forces demo mode)
  lifecycle   Full user lifecycle test
  clean       Remove test results
  help        Show this help

Options (lifecycle only):
  -u URL      Backend base URL (default: $BASE_URL)
  -v NUM      Number of virtual users (default: $VUS)
  -n NUM      Iterations per VU (default: $ITER)
  -m MODE     Register mode: 'demo' or 'register' (default: $REGISTER_MODE)
  -k PATH     Path to k6 binary (default: $K6)

Environment variables:
  PENPOT_BASE_URL      Same as -u
  PENPOT_REGISTER_MODE Same as -m
  K6                   Same as -k

Examples:
  $(basename "$0") smoke
  $(basename "$0") lifecycle -v 10 -n 5
  $(basename "$0") lifecycle -m register -v 5 -n 1
  $(basename "$0") lifecycle -u https://penpot.example.com
EOF
}

check_k6() {
  if ! command -v "$K6" &>/dev/null; then
    echo "Error: k6 not found at '$K6'" >&2
    echo "Install from https://k6.io/docs/get-started/installation/" >&2
    exit 1
  fi
}

run_k6() {
  local results_dir="$SCRIPT_DIR/results/$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$results_dir"

  echo "Penpot Performance Test"
  echo "  Base URL:       $BASE_URL"
  echo "  Register mode:  $REGISTER_MODE"
  echo "  VUs:            $VUS"
  echo "  Iterations:     $ITER"
  echo "  Results:        $results_dir"
  echo ""

  "$K6" run \
    --env "PENPOT_BASE_URL=$BASE_URL" \
    --env "PENPOT_REGISTER_MODE=$REGISTER_MODE" \
    --vus "$VUS" \
    --iterations "$ITER" \
    --out "json=$results_dir/k6-summary.json" \
    "$SCRIPT_DIR/scripts/lifecycle.js"
}

# ---------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------

cmd_smoke() {
  check_k6
  REGISTER_MODE=demo
  VUS=1
  ITER=1
  run_k6
}

cmd_lifecycle() {
  # Parse options
  while getopts "u:v:n:m:k:h" opt; do
    case "$opt" in
      u) BASE_URL="$OPTARG" ;;
      v) VUS="$OPTARG" ;;
      n) ITER="$OPTARG" ;;
      m) REGISTER_MODE="$OPTARG" ;;
      k) K6="$OPTARG" ;;
      h) usage; exit 0 ;;
      *) usage >&2; exit 1 ;;
    esac
  done

  check_k6
  run_k6
}

cmd_clean() {
  local results_dir="$SCRIPT_DIR/results"
  if [[ -d "$results_dir" ]]; then
    rm -rf "$results_dir"
    echo "Cleaned $results_dir"
  else
    echo "Nothing to clean"
  fi
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

if [[ $# -lt 1 ]]; then
  usage >&2
  exit 1
fi

command="$1"
shift

case "$command" in
  smoke)    cmd_smoke "$@" ;;
  lifecycle) cmd_lifecycle "$@" ;;
  clean)    cmd_clean "$@" ;;
  help|-h|--help) usage ;;
  *)
    echo "Unknown command: $command" >&2
    usage >&2
    exit 1
    ;;
esac
