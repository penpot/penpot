#!/usr/bin/env bash
# import-to-penpot.sh — wrapper to screenshot any web source and import into Penpot
# Usage: ./import-to-penpot.sh --help

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCREENSHOT_DIR="/tmp/portfolio-screenshots"
PIPELINE="$SCRIPT_DIR/screenshot-pipeline.mjs"
REPL_HOST="localhost:4403"

usage() {
  cat <<EOF
Usage: import-to-penpot.sh [SOURCE] [OPTIONS]

Sources (mutually exclusive):
  --local <port>          Local app on http://localhost:<port>
  --url <url>             Any URL (Vercel, GitHub Pages, etc.)
  --github <owner>/<repo> GitHub repo page https://github.com/<owner>/<repo>
  --from-file <path>      JSON file with {"base":"...","pages":[...]}

Options:
  --pages '<json>'        JSON array of page descriptors, e.g.
                          '[{"name":"home","path":"/"},{"name":"about","path":"/about"}]'
  --full-page             Capture full-page screenshot (default: viewport only)
  --help                  Show this help and exit

Examples:
  ./import-to-penpot.sh --local 4321
  ./import-to-penpot.sh --local 4321 --pages '[{"name":"home","path":"/"}]'
  ./import-to-penpot.sh --url https://mysite.vercel.app
  ./import-to-penpot.sh --github svaddadi/portfolio
  ./import-to-penpot.sh --from-file ~/my-pages.json --full-page
EOF
}

# ── Arg parsing ───────────────────────────────────────────────────────────────

SOURCE_TYPE=""
SOURCE_VALUE=""
PAGES_JSON=""
FULL_PAGE_FLAG=""

[[ $# -eq 0 ]] && { usage; exit 0; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --local)    SOURCE_TYPE="local";    SOURCE_VALUE="$2"; shift 2 ;;
    --url)      SOURCE_TYPE="url";      SOURCE_VALUE="$2"; shift 2 ;;
    --github)   SOURCE_TYPE="github";   SOURCE_VALUE="$2"; shift 2 ;;
    --from-file) SOURCE_TYPE="file";    SOURCE_VALUE="$2"; shift 2 ;;
    --pages)    PAGES_JSON="$2";        shift 2 ;;
    --full-page) FULL_PAGE_FLAG="--full-page"; shift ;;
    --help|-h)  usage; exit 0 ;;
    *) echo "Unknown option: $1"; usage; exit 1 ;;
  esac
done

# ── Resolve base URL and pages ────────────────────────────────────────────────

case "$SOURCE_TYPE" in
  local)
    BASE_URL="http://localhost:${SOURCE_VALUE}"
    ;;
  url)
    BASE_URL="$SOURCE_VALUE"
    ;;
  github)
    BASE_URL="https://github.com/${SOURCE_VALUE}"
    [[ -z "$PAGES_JSON" ]] && PAGES_JSON='[{"name":"repo","path":"/"}]'
    ;;
  file)
    BASE_URL=$(node -e "const f=require('fs').readFileSync('${SOURCE_VALUE}','utf8'); console.log(JSON.parse(f).base)")
    PAGES_JSON=$(node -e "const f=require('fs').readFileSync('${SOURCE_VALUE}','utf8'); console.log(JSON.stringify(JSON.parse(f).pages))")
    ;;
  *)
    echo "Error: no source specified. Use --local, --url, --github, or --from-file."
    usage; exit 1 ;;
esac

[[ -z "$PAGES_JSON" ]] && PAGES_JSON='[{"name":"home","path":"/"}]'

# ── Preflight checks ──────────────────────────────────────────────────────────

if ! command -v node &>/dev/null; then
  echo "Error: 'node' not found. Install Node.js first." >&2; exit 1
fi

if ! curl -sf -o /dev/null --connect-timeout 3 "http://${REPL_HOST}/execute" 2>/dev/null; then
  echo "Error: MCP REPL not reachable at ${REPL_HOST}." >&2
  echo "  → Open the Penpot workspace and load the MCP plugin." >&2
  exit 1
fi

if [[ "$SOURCE_TYPE" == "local" ]]; then
  if ! curl -sf -o /dev/null --connect-timeout 3 "$BASE_URL" 2>/dev/null; then
    echo "Error: local app not reachable at ${BASE_URL}." >&2
    echo "  → Start your dev server first (e.g. npm run dev)." >&2
    exit 1
  fi
elif [[ "$SOURCE_TYPE" == "url" ]]; then
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 5 "$BASE_URL" 2>/dev/null || echo "000")
  [[ "$STATUS" != "200" ]] && echo "Warning: ${BASE_URL} returned HTTP ${STATUS}. Continuing anyway..."
fi

# ── Run the pipeline ──────────────────────────────────────────────────────────

echo "Source:      $BASE_URL"
echo "Pages:       $PAGES_JSON"
echo "Full-page:   ${FULL_PAGE_FLAG:-no}"
echo ""

node "$PIPELINE" \
  --base "$BASE_URL" \
  --urls "$PAGES_JSON" \
  ${FULL_PAGE_FLAG:+"$FULL_PAGE_FLAG"}

echo ""
echo "Screenshots saved to: $SCREENSHOT_DIR"
