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
#   ./run.sh concurrent-edit                # Concurrent editing (same-file or multi-file)
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
DURATION=""
REGISTER_MODE="${PENPOT_REGISTER_MODE:-demo}"
K6="${K6:-k6}"
EDIT_MODE="${PENPOT_EDIT_MODE:-same-file}"
FILE_COUNT="${PENPOT_FILE_COUNT:-1}"
VUS_PER_FILE="${PENPOT_VUS_PER_FILE:-1}"
EDIT_ITERATIONS=""

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

usage() {
  cat <<EOF
Penpot Performance Tests

Usage:
  $(basename "$0") <command> [options]

Commands:
  smoke              1 VU, 1 iteration smoke test of the lifecycle flow
  lifecycle          Full user lifecycle (register → CRUD → delete)
  workspace-open     Read-heavy: repeatedly open a file (get-file, libraries, thumbnails)
  workspace-edit     Write-heavy: repeatedly edit a file (get-file + update-file loop)
  media-upload       Upload images of varying sizes (direct + chunked)
  font-upload        Upload fonts via chunked upload + create-font-variant
  concurrent-edit    Concurrent editing: same-file or multi-file mode
  file-size-matrix   Measure latency vs file size (10, 100, 500, 1000 shapes)
  compare            Compare two k6 JSON results for regression
  all                Run all scenarios together (orchestrator)
  clean              Remove test results
  help               Show this help

Options:
  -u URL             Backend base URL (default: $BASE_URL)
  -v NUM             Number of virtual users (default: per-script defaults)
  -n NUM             Iterations per VU (default: per-script defaults)
  -d DURATION        Test duration (e.g. 30s, 5m, 2h; default: k6 default)
  -m MODE            Register mode: 'demo' or 'register' (default: $REGISTER_MODE)
  -k PATH            Path to k6 binary (default: $K6)

Concurrent-edit options:
  --mode MODE             'same-file' or 'multi-file' (default: $EDIT_MODE)
  --files NUM             Number of files for multi-file mode (default: $FILE_COUNT)
  --vus-per-file NUM      VUs per file for multi-file mode (default: $VUS_PER_FILE)
  --edit-iterations NUM   Per-VU edit loop iterations (concurrent-edit, file-size-matrix; default: 10)

Environment variables:
  PENPOT_BASE_URL       Same as -u
  PENPOT_REGISTER_MODE  Same as -m
  PENPOT_EDIT_MODE      Same as --mode
  PENPOT_FILE_COUNT     Same as --files
  PENPOT_VUS_PER_FILE   Same as --vus-per-file
  PENPOT_EDIT_ITERATIONS  Same as --edit-iterations
  K6                    Same as -k
  PENPOT_DURATION       Same as -d

Examples:
  $(basename "$0") smoke
  $(basename "$0") lifecycle -v 5 -n 10
  $(basename "$0") workspace-edit -v 20 -n 50
  $(basename "$0") media-upload -u https://penpot.example.com
  $(basename "$0") concurrent-edit --mode same-file -v 5 -n 10
  $(basename "$0") concurrent-edit --mode multi-file --files 3 --vus-per-file 2 -n 10
  $(basename "$0") file-size-matrix -n 10
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
  local flags="--env PENPOT_BASE_URL=$BASE_URL --env PENPOT_REGISTER_MODE=$REGISTER_MODE --env PENPOT_EDIT_MODE=$EDIT_MODE --env PENPOT_FILE_COUNT=$FILE_COUNT --env PENPOT_VUS_PER_FILE=$VUS_PER_FILE"
  if [[ -n "${PENPOT_TOTAL_VUS:-}" ]]; then
    flags="$flags --env PENPOT_TOTAL_VUS=$PENPOT_TOTAL_VUS"
  fi
  if [[ -n "$VUS" ]]; then
    flags="$flags --env K6_VUS=$VUS"
  fi
  if [[ -n "$ITER" ]]; then
    flags="$flags --env K6_ITERATIONS=$ITER"
  fi
  if [[ -n "$EDIT_ITERATIONS" ]]; then
    flags="$flags --env PENPOT_EDIT_ITERATIONS=$EDIT_ITERATIONS"
  fi
  echo "$flags"
}

# Build k6 VU/iteration/duration flags (only if explicitly set)
k6_scale_flags() {
  local flags=""
  if [[ -n "$VUS" ]]; then
    flags="$flags --vus $VUS"
  fi
  if [[ -n "$ITER" ]]; then
    flags="$flags --iterations $ITER"
  elif [[ -n "$VUS" && -z "$DURATION" ]]; then
    # k6 requires iterations/duration/stages alongside --vus.
    # When only -v is given, default iterations to VUs so
    # iterations >= VUs (k6 constraint for shared-iterations).
    flags="$flags --iterations $VUS"
  fi
  if [[ -n "$DURATION" ]]; then
    flags="$flags --duration $DURATION"
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

cmd_concurrent_edit() {
  check_k6

  local label="concurrent-edit-${EDIT_MODE}"
  if [[ "$EDIT_MODE" == "multi-file" ]]; then
    label="${label}-${FILE_COUNT}files-${VUS_PER_FILE}vpu"
  fi

  echo ""
  echo "=== Concurrent Edit ($EDIT_MODE) ==="
  echo "  Mode:           $EDIT_MODE"
  if [[ "$EDIT_MODE" == "multi-file" ]]; then
    echo "  Files:          $FILE_COUNT"
    echo "  VUs per file:   $VUS_PER_FILE"
    VUS=$((FILE_COUNT * VUS_PER_FILE))
    echo "  Total VUs:      $VUS"
  else
    [[ -n "$VUS" ]] && echo "  VUs:            $VUS"
  fi
  [[ -n "$ITER" ]] && echo "  Iterations:     $ITER"
  echo ""

  # For same-file mode, pass VUS as PENPOT_TOTAL_VUS so setup() knows how many pages to create
  if [[ "$EDIT_MODE" == "same-file" && -n "$VUS" ]]; then
    export PENPOT_TOTAL_VUS="$VUS"
  fi

  run_script "workspace-edit-concurrent.js" "$label"
}

cmd_file_size_matrix() {
  check_k6

  echo ""
  echo "=== File Size Matrix ==="
  echo "  Tiers:          small(10), medium(100), large(500), xlarge(1000)"
  [[ -n "$EDIT_ITERATIONS" ]] && echo "  Iterations:     $EDIT_ITERATIONS (per tier)"
  echo ""

  run_script "file-size-matrix.js" "file-size-matrix"
}

cmd_compare() {
  local baseline="$1"
  local current="$2"
  local threshold="${3:-20}"

  if [[ -z "$baseline" || -z "$current" ]]; then
    echo "Usage: ./run.sh compare <baseline.json> <current.json> [threshold]"
    echo ""
    echo "Compare two k6 JSON results for performance regression."
    echo ""
    echo "Arguments:"
    echo "  baseline.json   k6 JSON output from base branch"
    echo "  current.json    k6 JSON output from PR branch"
    echo "  threshold       Fail if p95 increases > N% (default: 20)"
    exit 1
  fi

  if [[ ! -f "$baseline" ]]; then
    echo "Error: Baseline file not found: $baseline" >&2
    exit 1
  fi
  if [[ ! -f "$current" ]]; then
    echo "Error: Current file not found: $current" >&2
    exit 1
  fi

  node "$SCRIPT_DIR/scripts/compare-results.cjs" "$baseline" "$current" --threshold "$threshold"
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

# Parse global options first (before command)
parse_opts() {
  # First, extract long options (--mode, --files, --vus-per-file)
  local args=()
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --mode)
        EDIT_MODE="$2"
        shift 2
        ;;
      --files)
        FILE_COUNT="$2"
        shift 2
        ;;
      --vus-per-file)
        VUS_PER_FILE="$2"
        shift 2
        ;;
      --edit-iterations)
        EDIT_ITERATIONS="$2"
        shift 2
        ;;
      *)
        args+=("$1")
        shift
        ;;
    esac
  done

  # Apply PENPOT_DURATION env var as default (before CLI parsing takes precedence)
  if [[ -z "$DURATION" && -n "${PENPOT_DURATION:-}" ]]; then
    DURATION="$PENPOT_DURATION"
  fi

  # Now parse short options with getopts
  set -- "${args[@]}"
  OPTIND=1
  while getopts "u:v:n:d:m:k:h" opt; do
    case "$opt" in
      u) BASE_URL="$OPTARG" ;;
      v) VUS="$OPTARG" ;;
      n) ITER="$OPTARG" ;;
      d) DURATION="$OPTARG" ;;
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
    ;;
esac

case "$command" in
  smoke)           cmd_smoke ;;
  lifecycle)       cmd_lifecycle ;;
  workspace-open)  cmd_workspace_open ;;
  workspace-edit)  cmd_workspace_edit ;;
  media-upload)    cmd_media_upload ;;
  font-upload)     cmd_font_upload ;;
  concurrent-edit) cmd_concurrent_edit ;;
  file-size-matrix) cmd_file_size_matrix ;;
  compare)         cmd_compare "$@" ;;
  all)             cmd_all ;;
  clean)           cmd_clean ;;
  help|-h|--help)  usage ;;
  *)
    echo "Unknown command: $command" >&2
    usage >&2
    exit 1
    ;;
esac
