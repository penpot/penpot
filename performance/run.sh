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
#   ./run.sh lifecycle                      # Full user lifecycle
#   ./run.sh workspace-open                 # Read-heavy file open flow
#   ./run.sh workspace-edit                 # Write-heavy file edit loop
#   ./run.sh media-upload                   # Direct + chunked image uploads
#   ./run.sh font-upload                    # Chunked font upload + variant creation
#   ./run.sh all                            # Run all scenarios together (orchestrator)
#   ./run.sh clean                          # Remove test results

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------

BASE_URL="${PENPOT_BASE_URL:-http://localhost:6060}"
VUS=""
ITER=""
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
  smoke           1 VU, 1 iteration smoke test of the lifecycle flow
  lifecycle       Full user lifecycle (register → CRUD → delete)
  workspace-open  Read-heavy: repeatedly open a file (get-file, libraries, thumbnails)
  workspace-edit  Write-heavy: repeatedly edit a file (get-file + update-file loop)
  media-upload    Upload images of varying sizes (direct + chunked)
  font-upload     Upload fonts via chunked upload + create-font-variant
  all             Run all scenarios together (orchestrator)
  clean           Remove test results
  help            Show this help

Options:
  -u URL      Backend base URL (default: $BASE_URL)
  -v NUM      Number of virtual users (default: per-script defaults)
  -n NUM      Iterations per VU (default: per-script defaults)
  -m MODE     Register mode: 'demo' or 'register' (default: $REGISTER_MODE)
  -k PATH     Path to k6 binary (default: $K6)

Environment variables:
  PENPOT_BASE_URL      Same as -u
  PENPOT_REGISTER_MODE Same as -m
  K6                   Same as -k

Examples:
  $(basename "$0") smoke
  $(basename "$0") lifecycle -v 10 -n 5
  $(basename "$0") workspace-edit -v 20 -n 50
  $(basename "$0") media-upload -u https://penpot.example.com
  $(basename "$0") all -v 50
EOF
}

check_k6() {
  if ! command -v "$K6" &>/dev/null; then
    echo "Error: k6 not found at '$K6'" >&2
    echo "Install from https://k6.io/docs/get-started/installation/" >&2
    exit 1
  fi
}

# Build k6 env flags
k6_env_flags() {
  echo "--env PENPOT_BASE_URL=$BASE_URL --env PENPOT_REGISTER_MODE=$REGISTER_MODE"
}

# Build k6 VU/iteration flags (only if explicitly set)
k6_scale_flags() {
  local flags=""
  if [[ -n "$VUS" ]]; then
    flags="$flags --vus $VUS"
  fi
  if [[ -n "$ITER" ]]; then
    flags="$flags --iterations $ITER"
  fi
  echo "$flags"
}

# Run a single k6 script
run_script() {
  local script="$1"
  local label="$2"
  local results_dir="$SCRIPT_DIR/results/$(date +%Y%m%d-%H%M%S)-${label}"
  mkdir -p "$results_dir"

  echo ""
  echo "=== $label ==="
  echo "  Script:       scripts/${script}"
  echo "  Base URL:     $BASE_URL"
  echo "  Register mode: $REGISTER_MODE"
  [[ -n "$VUS" ]] && echo "  VUs:          $VUS"
  [[ -n "$ITER" ]] && echo "  Iterations:   $ITER"
  echo "  Results:      $results_dir"
  echo ""

  # shellcheck disable=SC2046
  $K6 run \
    $(k6_env_flags) \
    $(k6_scale_flags) \
    --out "json=$results_dir/k6-summary.json" \
    "$SCRIPT_DIR/scripts/${script}"
}

# Run all scenarios as parallel k6 processes
run_all() {
  local results_dir="$SCRIPT_DIR/results/$(date +%Y%m%d-%H%M%S)-all"
  mkdir -p "$results_dir"

  local default_vus="${VUS:-10}"

  echo ""
  echo "=== Penpot Performance Orchestrator ==="
  echo "  Base URL:      $BASE_URL"
  echo "  Total VUs:     $default_vus (distributed across flows)"
  echo "  Results:       $results_dir"
  echo ""
  echo "  Flow distribution:"
  echo "    lifecycle:       2 VUs  (full CRUD)"
  echo "    workspace-open:  3 VUs  (read-heavy)"
  echo "    workspace-edit:  3 VUs  (write-heavy)"
  echo "    media-upload:    1 VU   (storage I/O)"
  echo "    font-upload:     1 VU   (CPU/storage)"
  echo ""

  local pids=()

  # Lifecycle — full CRUD
  $K6 run \
    $(k6_env_flags) \
    --vus 2 --iterations 2 \
    --env "PENPOT_OPEN_ITERATIONS=3" \
    --out "json=$results_dir/lifecycle.json" \
    "$SCRIPT_DIR/scripts/lifecycle.js" &
  pids+=($!)

  # Workspace open — read-heavy
  $K6 run \
    $(k6_env_flags) \
    --vus 3 --iterations 3 \
    --env "PENPOT_OPEN_ITERATIONS=3" \
    --out "json=$results_dir/workspace-open.json" \
    "$SCRIPT_DIR/scripts/workspace-open.js" &
  pids+=($!)

  # Workspace edit — write-heavy
  $K6 run \
    $(k6_env_flags) \
    --vus 3 --iterations 5 \
    --env "PENPOT_EDIT_ITERATIONS=5" \
    --out "json=$results_dir/workspace-edit.json" \
    "$SCRIPT_DIR/scripts/workspace-edit.js" &
  pids+=($!)

  # Media upload
  $K6 run \
    $(k6_env_flags) \
    --vus 1 --iterations 2 \
    --out "json=$results_dir/media-upload.json" \
    "$SCRIPT_DIR/scripts/media-upload.js" &
  pids+=($!)

  # Font upload
  $K6 run \
    $(k6_env_flags) \
    --vus 1 --iterations 2 \
    --out "json=$results_dir/font-upload.json" \
    "$SCRIPT_DIR/scripts/font-upload.js" &
  pids+=($!)

  # Wait for all and collect exit codes
  local failed=0
  for pid in "${pids[@]}"; do
    if ! wait "$pid"; then
      failed=$((failed + 1))
    fi
  done

  echo ""
  if [[ $failed -gt 0 ]]; then
    echo "WARNING: $failed flow(s) had non-zero exit codes"
  fi
  echo "Results saved to: $results_dir"
}

# ---------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------

cmd_smoke() {
  check_k6
  REGISTER_MODE=demo
  VUS=1
  ITER=1
  run_script "lifecycle.js" "smoke"
}

cmd_lifecycle()       { check_k6; run_script "lifecycle.js" "lifecycle"; }
cmd_workspace_open()  { check_k6; run_script "workspace-open.js" "workspace-open"; }
cmd_workspace_edit()  { check_k6; run_script "workspace-edit.js" "workspace-edit"; }
cmd_media_upload()    { check_k6; run_script "media-upload.js" "media-upload"; }
cmd_font_upload()     { check_k6; run_script "font-upload.js" "font-upload"; }
cmd_all()             { check_k6; run_all; }

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

# Parse global options first (before command)
parse_opts() {
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
}

if [[ $# -lt 1 ]]; then
  usage >&2
  exit 1
fi

command="$1"
shift

# Parse options for flow commands (not smoke/clean/help/all)
case "$command" in
  smoke|clean|help|-h|--help)
    ;;
  *)
    parse_opts "$@"
    # Consume parsed opts
    while getopts "u:v:n:m:k:h" _ 2>/dev/null; do shift $((OPTIND - 1)); done 2>/dev/null || true
    ;;
esac

case "$command" in
  smoke)           cmd_smoke ;;
  lifecycle)       cmd_lifecycle ;;
  workspace-open)  cmd_workspace_open ;;
  workspace-edit)  cmd_workspace_edit ;;
  media-upload)    cmd_media_upload ;;
  font-upload)     cmd_font_upload ;;
  all)             cmd_all ;;
  clean)           cmd_clean ;;
  help|-h|--help)  usage ;;
  *)
    echo "Unknown command: $command" >&2
    usage >&2
    exit 1
    ;;
esac
