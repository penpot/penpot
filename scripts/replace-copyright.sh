#!/usr/bin/env bash
#
# replace-copyright.sh — Replace KALEIDOS INC copyright references with KALEIDOS SUBSIDIARY SL
#
# Usage:
#   scripts/replace-copyright.sh            # Execute replacements
#   scripts/replace-copyright.sh --dry-run  # Simulate without modifying files
#   scripts/replace-copyright.sh --help     # Show help
#

set -euo pipefail

# ── Configuration ──────────────────────────────────────────────────────────────

EXTENSIONS="clj|cljs|cljc|scss|js|jsx|mdx|md|sh|py|java"

# ── Colors ─────────────────────────────────────────────────────────────────────

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

# ── Globals ────────────────────────────────────────────────────────────────────

DRY_RUN=false
TOTAL_FILES=0
TOTAL_REPLACEMENTS=0
declare -A MODULE_FILES
declare -A MODULE_REPLACEMENTS

# ── Functions ──────────────────────────────────────────────────────────────────

usage() {
    cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Replace all "KALEIDOS INC" copyright references with "KALEIDOS SUBSIDIARY SL".
Only modifies files tracked by git.

Options:
  --dry-run    Show what would be changed without modifying files
  --help       Show this help message

Examples:
  $(basename "$0")              # Execute replacements
  $(basename "$0") --dry-run    # Preview changes
EOF
    exit 0
}

log_info() {
    echo -e "${CYAN}[INFO]${NC} $*"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $*"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $*" >&2
}

log_dry() {
    echo -e "${YELLOW}[DRY-RUN]${NC} $*"
}

get_module() {
    local file="$1"
    local first_dir
    first_dir=$(echo "$file" | cut -d'/' -f1)
    if [[ "$first_dir" == "$file" || "$first_dir" == "." ]]; then
        echo "root"
    else
        echo "$first_dir"
    fi
}

count_kaleidos() {
    local file="$1"
    rg -c "KALEIDOS INC" "$file" 2>/dev/null || echo "0"
}

replace_in_file() {
    local file="$1"
    local matches replacements module

    matches=$(count_kaleidos "$file")

    if [[ "$matches" -eq 0 ]]; then
        return
    fi

    if [[ "$DRY_RUN" == false ]]; then
        # Order matters: non-accented first, then full, then truncated
        perl -pi -e 's/KALEIDOS INC Sucursal en Espana SL/KALEIDOS SUBSIDIARY SL/g' "$file"
        perl -pi -e 's/KALEIDOS INC Sucursal en España SL/KALEIDOS SUBSIDIARY SL/g' "$file"
        perl -pi -e 's/KALEIDOS INC\b(?!\s+Sucursal)(?!\s+SUBSIDIARY)/KALEIDOS SUBSIDIARY SL/g' "$file"
        # Count remaining to compute actual replacements
        local remaining
        remaining=$(count_kaleidos "$file")
        replacements=$(( matches - remaining ))
    else
        # In dry-run mode, report all matches as potential replacements
        replacements=$matches
    fi

    if [[ "$replacements" -gt 0 ]]; then
        module=$(get_module "$file")
        if [[ "$DRY_RUN" == true ]]; then
            echo -e "  ${YELLOW}[${module}]${NC} ${file}: ${replacements} replacement(s)"
        else
            echo -e "  ${GREEN}[${module}]${NC} ${file}: ${replacements} replacement(s)"
        fi
        MODULE_REPLACEMENTS["$module"]=$(( ${MODULE_REPLACEMENTS["$module"]:-0} + replacements ))
        MODULE_FILES["$module"]=$(( ${MODULE_FILES["$module"]:-0} + 1 ))
        TOTAL_REPLACEMENTS=$(( TOTAL_REPLACEMENTS + replacements ))
        TOTAL_FILES=$(( TOTAL_FILES + 1 ))
    fi
}

print_summary() {
    echo ""
    echo -e "${BOLD}=== SUMMARY ===${NC}"
    printf "%-15s %6s %14s\n" "Module" "Files" "Replacements"
    printf "%-15s %6s %14s\n" "------" "-----" "------------"

    for module in $(printf '%s\n' "${!MODULE_REPLACEMENTS[@]}" | sort); do
        printf "%-15s %6d %14d\n" "$module" "${MODULE_FILES[$module]}" "${MODULE_REPLACEMENTS[$module]}"
    done

    printf "%-15s %6s %14s\n" "------" "-----" "------------"
    printf "${BOLD}%-15s %6d %14d${NC}\n" "TOTAL" "$TOTAL_FILES" "$TOTAL_REPLACEMENTS"
}

# ── Main ───────────────────────────────────────────────────────────────────────

main() {
    # Ensure we run from the repo root
    cd "$(git rev-parse --show-toplevel)"

    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --dry-run)
                DRY_RUN=true
                shift
                ;;
            --help|-h)
                usage
                ;;
            *)
                log_error "Unknown option: $1"
                usage
                ;;
        esac
    done

    # Header
    if [[ "$DRY_RUN" == true ]]; then
        log_dry "Starting copyright replacement (simulation mode)..."
    else
        log_info "Starting copyright replacement..."
    fi

    # Check dependencies
    for cmd in rg perl git; do
        if ! command -v "$cmd" &>/dev/null; then
            log_error "Required command not found: $cmd"
            exit 1
        fi
    done

    # Get tracked files matching our extensions
    local files
    files=$(git ls-files | grep -E "\.(${EXTENSIONS})$" || true)

    if [[ -z "$files" ]]; then
        log_warn "No tracked files found matching extensions: ${EXTENSIONS}"
        exit 0
    fi

    local file_count
    file_count=$(echo "$files" | wc -l)
    log_info "Found ${file_count} tracked files to scan"

    if [[ "$DRY_RUN" == true ]]; then
        log_dry "Would process files (no changes will be made)"
    fi

    echo ""

    # Process each file
    while IFS= read -r file; do
        replace_in_file "$file"
    done <<< "$files"

    # Print summary
    print_summary

    # Exit code
    if [[ "$DRY_RUN" == true ]]; then
        echo ""
        log_dry "No files were modified. Run without --dry-run to apply changes."
    fi
}

main "$@"
