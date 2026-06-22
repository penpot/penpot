#!/usr/bin/env bash

export ORGANIZATION="penpotapp";
export DEVENV_IMGNAME="$ORGANIZATION/devenv";
export DEVENV_NETWORK="penpot_shared";
export DEVENV_DEFAULTS_FILE="docker/devenv/defaults.env";

# Load instance configuration (project name, container names, ports, runtime
# config). Single source of truth for the devenv; consumed by both docker
# compose (via --env-file) and the shell logic below. Hard dependency — abort
# loudly if it's missing or unreadable.
#
# Host-shell env wins over file values: a value already set in the parent
# environment is preserved. This matches docker compose's own precedence rule
# for --env-file (so substitution-time and shell-time agree).
if [ ! -r "$DEVENV_DEFAULTS_FILE" ]; then
    echo "manage.sh: cannot read $DEVENV_DEFAULTS_FILE" >&2
    exit 1
fi
while IFS='=' read -r __key __value; do
    [[ -z "$__key" || "$__key" =~ ^[[:space:]]*# ]] && continue
    if [ -z "${!__key+x}" ]; then
        export "$__key=$__value"
    fi
done < "$DEVENV_DEFAULTS_FILE"
unset __key __value

# Source path for the workspace bind mount; consumed by docker-compose.main.yml.
# ws0 binds the live repo at $PWD; ws1+ override this in their overlay env file.
export PENPOT_SOURCE_PATH="${PENPOT_SOURCE_PATH:-$PWD}"

# Base directory under which non-main workspace clones live (one subdir per
# wsN, N>=1). Documented in defaults.env; default lives here so $HOME expands.
export PENPOT_WORKSPACES_DIR="${PENPOT_WORKSPACES_DIR:-$HOME/.penpot/penpot_workspaces}"

# Port allocation for parallel instances. Each wsN reserves a stride-wide port
# block starting at N*stride; ws0 sits at offset 0, so a per-service base port
# IS ws0's published port. To keep a single source of truth, the bases are
# derived from the ws0 values sourced from defaults.env above rather than
# duplicated here -- this makes it impossible for ws0's compose substitution and
# the ws1+ offset arithmetic to drift apart. `:?` aborts loudly if defaults.env
# is missing one. Consumed by instance-env-overrides (the values injected into
# the per-instance compose env) and print-instance-info (the startup URLs).
PENPOT_INSTANCE_PORT_STRIDE=10000
PENPOT_PORT_BASE_PUBLIC_HTTPS=${PENPOT_PUBLIC_HTTPS_PORT:?missing in defaults.env}
PENPOT_PORT_BASE_PUBLIC=${PENPOT_PUBLIC_HTTP_PORT:?missing in defaults.env}
PENPOT_PORT_BASE_MCP=${PENPOT_MCP_SERVER_PORT:?missing in defaults.env}
PENPOT_PORT_BASE_MCP_REPL=${PENPOT_MCP_REPL_PORT:?missing in defaults.env}
PENPOT_PORT_BASE_SERENA=${SERENA_EXTERNAL_PORT:?missing in defaults.env}
PENPOT_PORT_BASE_SERENA_DASHBOARD=${SERENA_DASHBOARD_EXTERNAL_PORT:?missing in defaults.env}
PENPOT_PORT_BASE_OPENCODE=${OPENCODE_EXTERNAL_PORT:?missing in defaults.env}
PENPOT_PORT_BASE_MDTS=${MDTS_EXTERNAL_PORT:?missing in defaults.env}
PENPOT_PORT_BASE_STORYBOOK=${PENPOT_STORYBOOK_PORT:?missing in defaults.env}

# Per-instance values like PENPOT_REDIS_URI are injected by
# instance-env-overrides as shell env variables (not set in this shell),
# because docker compose gives shell-env precedence over --env-file, letting
# per-instance values override the defaults.env baseline.

export CURRENT_USER_ID=$(id -u);
export CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD);

export IMAGEMAGICK_VERSION=7.1.2-24

# Safe directory to avoid ownership errors with Git
git config --global --add safe.directory /home/penpot/penpot || true

# Set default java options
export JAVA_OPTS=${JAVA_OPTS:-"-Xmx1000m -Xms50m"};

set -e

# ----------------------------------------------------------------------------
# Function map
#
# Utility helpers
#   print-current-version, setup-buildx, put-license-file
#
# Devenv image lifecycle
#   build-devenv, pull-devenv, pull-devenv-if-not-exists
#
# Devenv compose plumbing (used by every *-devenv command below)
#   ensure-devenv-network   create the external 'penpot_shared' network
#   infra-compose           wrap 'docker compose' for the shared-infra project
#   instance-compose        wrap 'docker compose' for one instance's main
#                           project, injecting that instance's overrides
#   instance-env-overrides  the per-instance KEY=VALUE overrides
#   devenv-main-container   resolve the 'main' container id via compose ps
#   devenv-main-running     true if 'main' is up
#
# Devenv bring-up commands (bring a workspace up + start background tmux)
#   run-devenv               bring one workspace up; supports --ws, --sync,
#                            --attach, --agentic (enables MCP + Serena), -e,
#                            --serena-context, git identity
#
# Devenv interactive entry points (operate on the running 'main' container)
#   attach-devenv            pure attach to the existing tmux session; fails
#                            fast if the devenv or session is missing
#   start-coding-agent       launches Claude Code or opencode against the
#                            current workspace's generated MCP config
#
# Production build pipeline
#   build                   one-shot 'docker run' that invokes a per-module
#                           build script inside the devenv image
#   build-<mod>-bundle      project a module's build output into ./bundles/
#   build-<mod>-docker-image  package a bundle into a release docker image
# ----------------------------------------------------------------------------

ARCH=$(uname -m)

if [[ "$ARCH" == "x86_64" || "$ARCH" == "amd64" || "$ARCH" == "i386" || "$ARCH" == "i686" ]]; then
    ARCH="amd64"
elif [[ "$ARCH" == "aarch64" || "$ARCH" == "arm64" ]]; then
    ARCH="arm64"
else
    echo "Unknown architecture $ARCH"
    exit -1
fi


function print-current-version {
    echo -n "$(git describe --tags --match "*.*.*")";
}

function setup-buildx {
    docker run --privileged --rm tonistiigi/binfmt --install all
    docker buildx inspect penpot > /dev/null 2>&1;

    if [ $? -eq 1 ]; then
        docker buildx create --name=penpot --use
        docker buildx inspect --bootstrap > /dev/null 2>&1;
    else
        docker buildx use penpot;
        docker buildx inspect --bootstrap  > /dev/null 2>&1;
    fi
}

function build-devenv {
    set +e;

    pushd docker/devenv;

    if [ "$1" = "--local" ]; then
        echo "Build local only $DEVENV_IMGNAME:latest image";
        docker build -t $DEVENV_IMGNAME:latest .;
    else
        echo "Build and push $DEVENV_IMGNAME:latest image";
        setup-buildx;

        docker buildx build \
          --platform linux/amd64,linux/arm64 \
          --output type=registry \
          -t $DEVENV_IMGNAME:latest .;

        docker pull $DEVENV_IMGNAME:latest;
    fi

    popd;
}

function pull-devenv {
    set -ex
    docker pull $DEVENV_IMGNAME:latest
}

function pull-devenv-if-not-exists {
    if [[ ! $(docker images $DEVENV_IMGNAME:latest -q) ]]; then
        pull-devenv $@
    fi
}

function ensure-devenv-network {
    docker network inspect "$DEVENV_NETWORK" >/dev/null 2>&1 || docker network create "$DEVENV_NETWORK" >/dev/null
}

# Compose-project plumbing for the parallel-workspaces layout.
#
# - Shared infrastructure (postgres, minio, mailer, ldap, minio-setup) runs
#   under project `penpotdev-infra`.
# - Shared infrastructure (postgres, minio, mailer, ldap, valkey, minio-setup)
#   runs under project `penpotdev-infra`.
# - Each runtime instance (ws0, ws1, ...) runs only its own main container
#   under project `penpotdev-wsN`. All workspaces uniformly overlay their
#   per-instance values via instance-env-overrides injected as shell env
#   variables (no files are written).
# `env -i` strips the ambient shell before invoking docker compose, then we
# re-inject exactly what compose needs. The stripping matters because
# defaults.env is sourced into manage.sh's own shell at startup, so otherwise
# those stale values would leak into substitution. And because Docker Compose
# gives shell-env precedence over --env-file, the re-injected per-instance
# overrides cleanly override the defaults.env baseline. Re-injected: HOME/PATH
# (tooling), CURRENT_USER_ID/PENPOT_SOURCE_PATH (always per-call), and the
# instance-env-overrides block.
function infra-compose {
    env -i HOME="$HOME" PATH="$PATH" PWD="$PWD" \
        docker compose -p penpotdev-infra \
            --env-file "$DEVENV_DEFAULTS_FILE" \
            -f docker/devenv/docker-compose.infra.yml \
            "$@"
}

function instance-compose {
    local instance="$1"; shift
    local source_path
    if [[ "$instance" == "ws0" ]]; then
        source_path="$PWD"
    else
        source_path="$(workspace-path "$instance")"
    fi

    # Per-instance overrides apply to all workspaces uniformly.
    mapfile -t overrides < <(instance-env-overrides "$instance")

    env -i HOME="$HOME" PATH="$PATH" PWD="$PWD" \
        CURRENT_USER_ID="${CURRENT_USER_ID:-$(id -u)}" \
        PENPOT_SOURCE_PATH="$source_path" \
        "${overrides[@]}" \
        docker compose -p "penpotdev-${instance}" \
            --env-file "$DEVENV_DEFAULTS_FILE" \
            -f docker/devenv/docker-compose.main.yml \
            "$@"
}

# Names of currently-running parallel instances (ws0, ws1, ...).
function list-running-instances {
    docker ps --format '{{.Label "com.docker.compose.project"}}' 2>/dev/null \
        | sort -u \
        | grep -oE '^penpotdev-ws[0-9]+$' \
        | sed 's/^penpotdev-//' \
        || true
}

function devenv-main-container {
    local instance="${1:-ws0}"
    # For ws1+, skip compose if the workspace clone doesn't exist yet — the
    # instance has never been set up, so there is no container to find.
    if [[ "$instance" != "ws0" && ! -d "$(workspace-path "$instance")" ]]; then
        return 0
    fi
    instance-compose "$instance" ps -q main 2>/dev/null
}

function devenv-main-running {
    local instance="${1:-ws0}"
    local container
    container=$(devenv-main-container "$instance")
    [[ -n "$container" ]] && [[ "$(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null)" = "true" ]]
}

# Bring shared infra up and block until minio-setup has provisioned the
# shared MinIO user/policy. Idempotent: a second call when everything is
# already up returns immediately.
function ensure-infra-up {
    infra-compose up -d
    local setup_container
    setup_container=$(infra-compose ps -aq minio-setup 2>/dev/null)
    if [[ -n "$setup_container" ]]; then
        docker wait "$setup_container" >/dev/null 2>&1 || true
    fi
}

# Refuse to sync workspaces if the live repo is in a fragile Git state.
# Copying a partial rebase/merge/cherry-pick into all workspaces would leave
# every instance in the same broken state.
function assert-clean-git-state {
    local fragile=""
    [ -d .git/rebase-apply ] && fragile="$fragile rebase-apply"
    [ -d .git/rebase-merge ] && fragile="$fragile rebase-merge"
    [ -f .git/MERGE_HEAD ]    && fragile="$fragile merge"
    [ -f .git/CHERRY_PICK_HEAD ] && fragile="$fragile cherry-pick"
    [ -f .git/index.lock ]    && fragile="$fragile index.lock"
    if [[ -n "$fragile" ]]; then
        echo "Live repo Git state is unsafe to copy into workspaces:$fragile" >&2
        echo "Finish or abort the in-progress operation, then retry." >&2
        return 1
    fi
}

# Absolute path of the workspace directory for a non-ws0 instance.
function workspace-path {
    local instance="$1"
    echo "${PENPOT_WORKSPACES_DIR}/${instance}"
}

# Echo the host port that <base> maps to for <instance>.
function instance-port {
    local instance="$1"
    local base="$2"
    local n=0
    [[ "$instance" =~ ^ws([0-9]+)$ ]] && n="${BASH_REMATCH[1]}"
    echo $(( base + n * PENPOT_INSTANCE_PORT_STRIDE ))
}

# Echo the per-instance Compose variable overrides for a workspace, one
# KEY=VALUE per line, for instance-compose to inject into its `env -i` line.
# Compose gives shell-env precedence over --env-file, so these override the
# defaults.env baseline. Every value is a pure function of the instance number,
# so nothing is persisted: they are recomputed on every compose invocation and
# can never drift from this logic. Called for every workspace (ws0, ws1, ...).
# All overrides are pure functions of the instance number; no per-instance
# post-processing is needed.
#
# Omitted on purpose: COMPOSE_PROJECT_NAME (set via compose's -p flag),
# PENPOT_SOURCE_PATH (injected directly by instance-compose), and
function instance-env-overrides {
    local instance="$1"
    local n=0
    [[ "$instance" =~ ^ws([0-9]+)$ ]] && n="${BASH_REMATCH[1]}"
    local public_https public mcp mcp_repl serena serena_dash
    public_https=$(instance-port "$instance" "$PENPOT_PORT_BASE_PUBLIC_HTTPS")
    public=$(instance-port "$instance" "$PENPOT_PORT_BASE_PUBLIC")
    mcp=$(instance-port "$instance" "$PENPOT_PORT_BASE_MCP")
    mcp_repl=$(instance-port "$instance" "$PENPOT_PORT_BASE_MCP_REPL")
    serena=$(instance-port "$instance" "$PENPOT_PORT_BASE_SERENA")
    serena_dash=$(instance-port "$instance" "$PENPOT_PORT_BASE_SERENA_DASHBOARD")
    opencode=$(instance-port "$instance" "$PENPOT_PORT_BASE_OPENCODE")
    mdts=$(instance-port "$instance" "$PENPOT_PORT_BASE_MDTS")
    storybook=$(instance-port "$instance" "$PENPOT_PORT_BASE_STORYBOOK")
    printf '%s\n' \
        "PENPOT_MAIN_CONTAINER_NAME=penpot-devenv-${instance}-main" \
        "PENPOT_USER_DATA_VOLUME=penpotdev_${instance}_user_data" \
        "PENPOT_PUBLIC_URI=https://localhost:${public_https}" \
        "PENPOT_REDIS_URI=redis://valkey/${n}" \
        "PENPOT_PUBLIC_HTTPS_PORT=${public_https}" \
        "PENPOT_PUBLIC_HTTP_PORT=${public}" \
        "PENPOT_MCP_SERVER_PORT=${mcp}" \
        "PENPOT_MCP_REPL_PORT=${mcp_repl}" \
        "PENPOT_STORYBOOK_PORT=${storybook}" \
        "SERENA_EXTERNAL_PORT=${serena}" \
        "OPENCODE_EXTERNAL_PORT=${opencode}" \
        "MDTS_EXTERNAL_PORT=${mdts}" \
        "SERENA_DASHBOARD_EXTERNAL_PORT=${serena_dash}" \
        "SHADOW_SERVER_URL=wss://localhost:${public_https}" \
        "PENPOT_TENANT=devenv-${instance}"
}

# Thin wrapper around .devenv/scripts/merge-mcp-config.py for the JSON clients
# (Claude Code, opencode, VS Code). The script does the actual envsubst + JSON
# deep-merge; see its docstring for the contract. ${PENPOT_MCP_PORT} /
# ${SERENA_MCP_PORT} placeholders in the template are resolved from the
# caller's environment. Any extra flags (e.g. --merge-into-existing for the VS
# Code output) are forwarded verbatim.
#
# Codex deliberately has no wrapper here: it cannot load an MCP config from an
# arbitrary file path, so instead of writing a file we inject our servers as
# `-c` overrides built at launch time -- see start-coding-agent.
function _merge-mcp-config-json {
    local shared="$1" tpl="$2" out="$3" key="$4"; shift 4
    python3 .devenv/scripts/merge-mcp-config.py \
        --format json --key "$key" "$@" \
        "$shared" "$tpl" "$out"
}

# Generate the per-workspace AI-client MCP config files by merging the
# committed .devenv/shared/<tool>.* with the port-substituted
# .devenv/templates/<tool>.*. Developers who want to override entries should
# use the client's own override mechanism: Claude Code's local scope, a
# project-level opencode.json, a VS Code user-profile entry, or a user-level
# ~/.codex/config.toml.
#
# Generated paths per tool:
#   <workspace>/.devenv/mcp/claude-code.json   loaded via --mcp-config; clean
#                                              overwrite (dedicated gitignored
#                                              file, no developer content)
#   <workspace>/.devenv/mcp/opencode.json      loaded via OPENCODE_CONFIG=; same
#   <workspace>/.vscode/mcp.json               auto-loaded by VS Code Copilot;
#                                              DEEP-MERGED into any existing file
#                                              so a developer's own entries on
#                                              ws0 survive (ws0's file IS the
#                                              live repo's; ws1+ start fresh).
#                                              Ours win on name collision.
#
# Codex is intentionally NOT generated here. It cannot load an MCP config from
# an arbitrary path, and writing the auto-discovered .codex/config.toml would
# clobber the developer's project-level Codex config on ws0. Instead its
# servers are injected as `-c` overrides at launch -- see start-coding-agent.
function write-instance-mcp-configs {
    local instance="$1"
    local workspace
    if [[ "$instance" == "ws0" ]]; then
        workspace="$PWD"
    else
        workspace=$(workspace-path "$instance")
    fi

    local src_dir="$workspace/.devenv"
    if [[ ! -d "$src_dir/shared" || ! -d "$src_dir/templates" ]]; then
        echo "[$instance] .devenv/shared or .devenv/templates missing under $workspace; skipping MCP config generation." >&2
        return 0
    fi

    local mcp_dir="$src_dir/mcp"
    mkdir -p "$mcp_dir" "$workspace/.vscode"

    PENPOT_MCP_PORT=$(instance-port "$instance" "$PENPOT_PORT_BASE_MCP")
    SERENA_MCP_PORT=$(instance-port "$instance" "$PENPOT_PORT_BASE_SERENA")
    export PENPOT_MCP_PORT SERENA_MCP_PORT

    _merge-mcp-config-json \
        "$src_dir/shared/claude-code.json" \
        "$src_dir/templates/claude-code.json" \
        "$mcp_dir/claude-code.json" \
        mcpServers
    _merge-mcp-config-json \
        "$src_dir/shared/opencode.json" \
        "$src_dir/templates/opencode.json" \
        "$mcp_dir/opencode.json" \
        mcp
    # VS Code's mcp.json is auto-discovered at a fixed path, so on ws0 it IS the
    # developer's own project file -- deep-merge into it rather than overwriting.
    # On ws1+ the path does not exist yet, so this writes it from scratch.
    _merge-mcp-config-json \
        "$src_dir/shared/vscode.json" \
        "$src_dir/templates/vscode.json" \
        "$workspace/.vscode/mcp.json" \
        servers \
        --merge-into-existing
}

# Seed (or re-seed) a workspace from the live repo, then switch it onto a
# unique branch. Two-step sync:
#   1. .git directory is rsync'd directly (so the workspace has its own
#      clone with the developer's current commits / index).
#   2. Working-tree files are enumerated by `git ls-files`, which is the
#      only authoritative source for "what files belong in the working
#      tree" (Git tracks files even when their parent directory matches
#      a gitignore pattern, e.g. .clj-kondo/config.edn). Using rsync's
#      gitignore filter directly misses those.
# Gitignored caches already in the workspace (node_modules, target, etc.)
# are left in place: no --delete on the working-tree pass.
function sync-workspace {
    local instance="$1"
    if [[ "$instance" == "ws0" ]]; then
        return 0
    fi
    assert-clean-git-state || return 1

    local workspace
    workspace=$(workspace-path "$instance")
    mkdir -p "$workspace"

    echo "[$instance] syncing workspace at $workspace ..."

    # .git directory — direct mirror, including index, refs, hooks, etc.
    rsync -a --delete "$PWD/.git/" "$workspace/.git/"

    # Working-tree files: tracked + untracked-not-ignored. git ls-files
    # speaks Git's actual semantics, including the "tracked overrides
    # gitignore" rule. --files-from feeds the path list to rsync verbatim.
    local files
    files=$(mktemp)
    git -C "$PWD" ls-files -z --cached --others --exclude-standard >"$files"
    rsync -a --files-from="$files" --from0 "$PWD/" "$workspace/"
    rm -f "$files"

    # Initial seed of frontend/resources/public/js/config.js. The file is
    # gitignored, so git ls-files would not list it, yet the agentic devenv
    # needs it (enable-mcp flag). After the first sync the workspace copy
    # belongs to the user — subsequent syncs leave it untouched.
    local cfg="frontend/resources/public/js/config.js"
    if [[ -f "$PWD/$cfg" && ! -f "$workspace/$cfg" ]]; then
        install -D "$PWD/$cfg" "$workspace/$cfg"
    fi

    (
        cd "$workspace"
        git switch -C "${instance}/${CURRENT_BRANCH}" >/dev/null
    )
}

function create-devenv {
    pull-devenv-if-not-exists $@;
    ensure-devenv-network;

    infra-compose create
    instance-compose ws0 create
}

# Stop instances. ws0 is the worker-bearer and must be the last one to stop;
# shared infra is shut down together with ws0. Flags are mutually exclusive.
#
#   --ws N (N>=1)  stop just that workspace. Leaves ws0 and infra alone.
#   --ws 0 | (no flag)  stop ws0 + shared infra. Refused if any ws1+ is running.
#   --all          stop every wsN highest-first, then ws0, then infra.
function stop-devenv {
    local target=""
    local all=false
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --ws)
                target="$(parse-ws-integer "$2")" || return 1; shift 2;;
            --all)
                all=true; shift;;
            *)
                echo "stop-devenv: unknown argument '$1'" >&2
                return 1;;
        esac
    done
    if [[ -n "$target" && "$all" == "true" ]]; then
        echo "stop-devenv: --ws and --all are mutually exclusive." >&2
        return 1
    fi

    local running ws
    running=$(list-running-instances)

    if [[ "$all" == "true" ]]; then
        # Highest wsN first, then ws0, then infra. ws0 stop also brings infra.
        for ws in $(printf '%s\n' $running | grep -v '^ws0$' | sed 's/^ws//' | sort -rn | sed 's/^/ws/'); do
            stop-instance "$ws"
        done
        if printf '%s\n' $running | grep -qx ws0; then
            stop-instance ws0
        fi
        infra-compose down -t 2
        return 0
    fi

    # Default target: ws0 (which also stops infra).
    [[ -z "$target" ]] && target="ws0"

    if [[ "$target" == "ws0" ]]; then
        local non_main=""
        for ws in $running; do
            [[ "$ws" != "ws0" ]] && non_main="$non_main $ws"
        done
        if [[ -n "$non_main" ]]; then
            echo "stop-devenv: cannot stop ws0 while other instances are running:${non_main}." >&2
            echo "Stop them first (--ws N) or use --all." >&2
            return 1
        fi
        if printf '%s\n' $running | grep -qx ws0; then
            stop-instance ws0
        else
            echo "[ws0] not running."
        fi
        infra-compose down -t 2
        return 0
    fi

    # --ws N (N>=1): stop just that instance, leave ws0 + infra up.
    if printf '%s\n' $running | grep -qx "$target"; then
        stop-instance "$target"
    else
        echo "[$target] not running."
    fi
}

# drop-devenv shares stop-devenv's CLI and invariants exactly; the only
# difference is that on a full teardown it also removes the devenv image
# (forcing the next bring-up to re-pull/rebuild). Single-workspace drops
# keep the image because the rest of the workspaces still depend on it.
#
#   --ws N (N >= 1)  delegate to stop-devenv; image is kept.
#   --ws 0 | (none)  delegate to stop-devenv; image is removed.
#   --all            delegate to stop-devenv; image is removed.
function drop-devenv {
    # Parse args ourselves to decide whether the image gets removed.
    # stop-devenv then re-parses the same flags and runs the actual stop.
    local target=""
    local all=false
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --ws)
                target="$(parse-ws-integer "$2")" || return 1; shift 2;;
            --all)
                all=true; shift;;
            *)
                echo "drop-devenv: unknown argument '$1'" >&2
                return 1;;
        esac
    done
    if [[ -n "$target" && "$all" == "true" ]]; then
        echo "drop-devenv: --ws and --all are mutually exclusive." >&2
        return 1
    fi

    local stop_args=()
    [[ -n "$target" ]] && stop_args+=(--ws "${target#ws}")
    [[ "$all" == "true" ]] && stop_args+=(--all)
    stop-devenv "${stop_args[@]}" || return $?

    # Image removal happens for the full-teardown paths only. A single-wsN
    # (N >= 1) drop must keep the image since ws0 and any other wsN still
    # rely on it.
    if [[ -z "$target" || "$target" == "ws0" ]] || [[ "$all" == "true" ]]; then
        echo "Clean old development image $DEVENV_IMGNAME..."
        docker images $DEVENV_IMGNAME -q | xargs --no-run-if-empty docker rmi
    fi
}

function log-devenv {
    local target="ws0"
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --ws)
                target="$(parse-ws-integer "$2")" || return 1; shift 2;;
            *)
                echo "log-devenv: unknown argument '$1'" >&2
                return 1;;
        esac
    done
    instance-compose "$target" logs -f --tail=50
}

# Strict parser for --ws values. Accepts a bare integer in the supported
# range (0..PENPOT_MAX_WS_INDEX) and returns the canonical "wsN" form.
# Anything else fails fast. The upper bound exists because the host ports
# computed for higher N overflow the 16-bit TCP port range:
#   port = base + N * PENPOT_INSTANCE_PORT_STRIDE (10000)
# With the current Serena bases (14181/14182), N = 5 still fits inside
# 65535 (64181/64182) but N = 6 overflows (74181/74182), so the cap is 5.
PENPOT_MAX_WS_INDEX=5
function parse-ws-integer {
    local raw="$1"
    if [[ ! "$raw" =~ ^[0-9]+$ ]]; then
        echo "Invalid --ws value: '$raw' (expected a non-negative integer, e.g. --ws 0, --ws 1, --ws 2)" >&2
        return 1
    fi
    if (( raw > PENPOT_MAX_WS_INDEX )); then
        echo "Invalid --ws value: '$raw' (max supported is --ws $PENPOT_MAX_WS_INDEX; higher indexes would overflow the 16-bit TCP port range)" >&2
        return 1
    fi
    echo "ws$raw"
}


# Bring a single instance up: compose up + detached tmux start. When agentic
# is true (the default) the tmux session gets MCP + Serena enabled; when false
# it is a plain non-agentic workspace (no MCP, no Serena). Workspace sync and
# env-file generation are the caller's responsibility (run-devenv handles
# them for ws1+).
function start-instance {
    local instance="$1"
    local serena_context="${2:-}"
    local git_user_name="${3:-}"
    local git_user_email="${4:-}"
    local agentic="${5:-true}"

    instance-compose "$instance" up -d main

    # Wait briefly for main to be reachable; the tmux session lives inside.
    local container deadline
    container=$(devenv-main-container "$instance")
    deadline=$(( SECONDS + 30 ))
    while ! docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null | grep -q true; do
        [[ $SECONDS -ge $deadline ]] && {
            echo "[${instance}] main container did not reach Running within 30s" >&2
            return 1
        }
        sleep 1
    done

    # Ensure /home/penpot is writable by the penpot user before touching
    # any files inside it (e.g. .gitconfig). start-tmux.sh also does this
    # but runs later asynchronously, so the container may still be root-owned
    # from a fresh volume mount at this point.
    docker exec "$container" sudo chown penpot:users /home/penpot 2>/dev/null || true

    # Seed the container's global git config from the values resolved on the
    # host so commits made inside the devenv carry a real author/committer. Empty
    # values are skipped — the host-identity warning is the caller's job.
    if [[ -n "$git_user_name" ]]; then
        docker exec "$container" sudo -u penpot git config --global user.name "$git_user_name"
    fi
    if [[ -n "$git_user_email" ]]; then
        docker exec "$container" sudo -u penpot git config --global user.email "$git_user_email"
    fi

    # Detached tmux so callers don't block on attach. Agentic mode adds the
    # MCP and Serena env vars that start-tmux.sh checks.
    local -a tmux_env=(-e PENPOT_TMUX_ATTACH=false)
    if [[ "$agentic" == "true" ]]; then
        tmux_env+=(
            -e PENPOT_FLAGS="${PENPOT_FLAGS:-} enable-mcp"
            -e SERENA_ENABLED=true
            -e SERENA_CONTEXT="$serena_context"
        )
    fi
    docker exec -d \
        "${tmux_env[@]}" \
        "$container" \
        sudo -EH -u penpot PENPOT_PLUGIN_DEV="${PENPOT_PLUGIN_DEV:-}" /home/start-tmux.sh
}

# Stop and remove one instance's containers without touching its volumes or
# its on-disk workspace directory (rule: never wipe data).
function stop-instance {
    local instance="$1"
    instance-compose "$instance" down -t 2
}

# Print per-instance URLs (Penpot UI, MCP stream endpoint, Serena, attach
# command) for one instance.
function print-instance-info {
    local instance="$1"
    local public mcp serena serena_dash
    public=$(instance-port "$instance" "$PENPOT_PORT_BASE_PUBLIC")
    public_https=$(instance-port "$instance" "$PENPOT_PORT_BASE_PUBLIC_HTTPS")
    mcp=$(instance-port "$instance" "$PENPOT_PORT_BASE_MCP")
    serena=$(instance-port "$instance" "$PENPOT_PORT_BASE_SERENA")
    opencode=$(instance-port "$instance" "$PENPOT_PORT_BASE_OPENCODE")
    serena_dash=$(instance-port "$instance" "$PENPOT_PORT_BASE_SERENA_DASHBOARD")

    # --ws takes a bare integer; ws0 is the default, so its flag is elided.
    local n="${instance#ws}"
    local ws_flag=""
    [[ "$instance" != "ws0" ]] && ws_flag=" --ws ${n}"

    echo
    echo "[$instance]"
    echo "  Penpot UI:           https://localhost:${public_https}"
    echo "  Penpot UI:           http://localhost:${public}"
    echo "  MCP stream:          http://localhost:${mcp}/mcp"
    echo "  OpenCode Server:     http://localhost:${opencode}"
    echo "  Serena MCP:          http://localhost:${serena}"
    echo "  Serena dashboard:    http://localhost:${serena_dash}"
    echo "  Attach:              ./manage.sh attach-devenv${ws_flag}"
    echo "  Coding agent:        ./manage.sh start-coding-agent claude${ws_flag}  (or: opencode|vscode|codex)"
}

# Bring a single workspace up. Without --agentic it's non-agentic (no MCP, no
# Serena); with --agentic it enables MCP + Serena for AI-driven development.
# Supports --ws for parallel workspace targets; ws0 is the default.
function run-devenv {
    local target="ws0"
    local do_sync=false
    local do_attach=false
    local agentic=false
    local serena_context="desktop-app"
    local git_user_name=""
    local git_user_email=""
    local -a extra_env_args=()

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --ws)
                target="$(parse-ws-integer "$2")" || return 1; shift 2;;
            --sync)
                do_sync=true; shift;;
            --agentic)
                agentic=true; shift;;
            --attach)
                do_attach=true; shift;;
            --serena-context)
                serena_context="$2"; shift 2;;
            --git-user-name)
                git_user_name="$2"; shift 2;;
            --git-user-email)
                git_user_email="$2"; shift 2;;
            -e)
                extra_env_args+=(-e "$2"); shift 2;;
            -e*)
                extra_env_args+=(-e "${1#-e}"); shift;;
            -h|--help)
                echo "Usage: run-devenv [--ws N] [--sync] [--attach] [--agentic] [--serena-context CTX] [--git-user-name NAME] [--git-user-email EMAIL] [-e KEY=VAL]"
                echo "  Bring a single workspace up."
                echo "  --ws N                target workspace (default: 0)."
                echo "  --sync                re-seed the wsN clone from the live repo (forbidden on ws0)."
                echo "  --attach              attach to the tmux session after startup."
                echo "  --agentic             enable MCP + Serena (AI-agent mode)."
                echo "  --serena-context CTX  context passed to Serena (default: desktop-app)."
                echo "  --git-user-name NAME  git author name inside the container (default: host git config)."
                echo "  --git-user-email EMAIL  git author email inside the container."
                echo "  -e KEY=VAL            forward env var to docker exec on attach."
                return 0;;
            *)
                echo "run-devenv: unknown argument '$1' (use --help for usage)" >&2
                return 1;;
        esac
    done

    if [[ "$target" == "ws0" && "$do_sync" == "true" ]]; then
        echo "run-devenv: --sync is not allowed on main (ws0)." >&2
        return 1
    fi

    # Pre-flight: config.js must exist for agentic mode. The file is gitignored;
    # without it the frontend never sets 'enable-mcp', so the agent can't drive
    # Penpot via MCP.
    if [[ "$agentic" == "true" ]]; then
        local cfg="frontend/resources/public/js/config.js"
        if [[ ! -f "$PWD/$cfg" ]]; then
            echo "$cfg is missing in the live repo." >&2
            echo "Create it before running with --agentic -- the file is gitignored," >&2
            echo "read directly from \$PWD on ws0 and copied into wsN only on its initial" >&2
            echo "sync. Without it the Penpot frontend will not establish the MCP" >&2
            echo "connection, so the agent cannot drive it. Minimal content:" >&2
            echo "  var penpotFlags = \"enable-mcp\";" >&2
            return 1
        fi
    fi

    # Resolve git identity from the host when flags are omitted.
    if [[ -z "$git_user_name" ]]; then
        git_user_name="$(git config user.name 2>/dev/null || true)"
    fi
    if [[ -z "$git_user_email" ]]; then
        git_user_email="$(git config user.email 2>/dev/null || true)"
    fi
    if [[ -z "$git_user_name" || -z "$git_user_email" ]]; then
        echo "[$target] warning: host git identity is incomplete (name='${git_user_name}', email='${git_user_email}')." >&2
        echo "  Commits made inside the devenv will fail until you set it via --git-user-name / --git-user-email" >&2
        echo "  or 'git config user.{name,email}' on the host." >&2
    fi

    if devenv-main-running "$target"; then
        echo "run-devenv: instance '$target' is already running." >&2
        return 1
    fi

    pull-devenv-if-not-exists
    ensure-devenv-network
    ensure-infra-up

    if [[ "$target" != "ws0" ]]; then
        local workspace
        workspace=$(workspace-path "$target")
        if [[ ! -d "$workspace" ]]; then
            echo "[$target] workspace at $workspace does not exist; performing initial sync."
            do_sync=true
        fi
        if [[ "$do_sync" == "true" ]]; then
            sync-workspace "$target"
        fi
    fi

    if [[ "$agentic" == "true" ]]; then
        write-instance-mcp-configs "$target"
    fi

    echo "Starting $target..."
    start-instance "$target" "$serena_context" "$git_user_name" "$git_user_email" "$agentic"
    print-instance-info "$target"

    if [[ "$do_attach" == "true" ]]; then
        local container
        container=$(devenv-main-container "$target")
        echo "[$target] waiting for tmux session..."
        local deadline=$(( SECONDS + 120 ))
        while ! docker exec "$container" sudo -EH -u penpot tmux has-session -t penpot 2>/dev/null; do
            [[ $SECONDS -ge $deadline ]] && {
                echo "[$target] tmux session did not appear within 120s" >&2
                return 1
            }
            sleep 2
        done
        echo "[$target] attaching to tmux session..."
        docker exec -ti \
            "${extra_env_args[@]}" \
            "$container" sudo -EH -u penpot tmux attach -t penpot
    fi
}

function attach-devenv {
    local instance="ws0"
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --ws)
                instance="$(parse-ws-integer "$2")" || return 1; shift 2;;
            *)
                echo "attach-devenv: unknown argument '$1'" >&2
                return 1;;
        esac
    done

    if ! devenv-main-running "$instance"; then
        echo "Instance '$instance' is not running." >&2
        echo "Start it first with './manage.sh run-devenv [--ws N] [--agentic]'." >&2
        return 1
    fi

    local session="penpot"
    local container
    container=$(devenv-main-container "$instance")

    if ! docker exec "$container" sudo -EH -u penpot tmux has-session -t "$session" 2>/dev/null; then
        echo "No tmux session '$session' inside instance '$instance'." >&2
        echo "The session may still be starting (the workspace's startup script runs the" >&2
        echo "project setup before creating it) or it may have been closed. Wait and retry." >&2
        return 1
    fi

    docker exec -ti "$container" sudo -EH -u penpot tmux attach -t "$session"
}

# Launch an AI coding agent against one parallel devenv workspace with the
# right MCP config wired in. The generated config enhances rather than
# replaces the developer's global client config; see .devenv/README.md for
# the precedence rules and override paths.
#
# Target selection:
#   no flag    → ws0 (the live repo at $PWD).
#   --ws N     → wsN's workspace clone at ${PENPOT_WORKSPACES_DIR}/wsN
#                (N is an integer; non-integer values are rejected).
# Before launching, the function cd's into the resolved workspace and refuses
# to start unless the target instance's 'main' container is up — the Penpot
# and Serena MCP servers only exist while the devenv is running.
#
# Per-client launch behaviour:
#   claude    exec'd with --mcp-config <workspace>/.devenv/mcp/claude-code.json
#   opencode  exec'd with OPENCODE_CONFIG=<workspace>/.devenv/mcp/opencode.json
#   vscode    'code' launched on the workspace; .vscode/mcp.json is
#             auto-discovered by GitHub Copilot
#   codex     'codex' exec'd from the workspace with our servers passed as
#             `-c mcp_servers.<name>....` overrides (built fresh from the
#             committed templates). Nothing is written to .codex/config.toml,
#             so the developer's own Codex config is left untouched.
#
# Usage: ./manage.sh start-coding-agent <claude|opencode|vscode|codex> [--ws N] [...passthrough]
function start-coding-agent {
    local client="${1:-}"
    [[ $# -gt 0 ]] && shift

    case "$client" in
        ""|-h|--help)
            echo "Usage: $0 start-coding-agent <claude|opencode|vscode|codex> [--ws N] [...passthrough]" >&2
            return 1
            ;;
        claude|opencode|vscode|codex)
            ;;
        *)
            echo "start-coding-agent: unknown client '$client' (expected one of claude, opencode, vscode, codex)." >&2
            return 1
            ;;
    esac

    local instance="ws0"
    if [[ $# -gt 0 && "$1" == "--ws" ]]; then
        instance="$(parse-ws-integer "$2")" || return 1
        shift 2
    fi

    # --ws is the default-elided flag: only emit it in suggestion strings for
    # ws1+; ws0 is the default target so 'run-devenv --agentic' is the right hint.
    local ws_flag=""
    [[ "$instance" != "ws0" ]] && ws_flag=" --ws ${instance#ws}"

    # Resolve the workspace directory for the target instance. ws0 binds
    # the live repo; ws1+ are clones under PENPOT_WORKSPACES_DIR.
    local workspace
    if [[ "$instance" == "ws0" ]]; then
        workspace="$PWD"
    else
        workspace="$(workspace-path "$instance")"
        if [[ ! -d "$workspace" ]]; then
            echo "start-coding-agent: workspace for $instance not found at $workspace." >&2
            echo "Bring '$instance' up first with './manage.sh run-devenv${ws_flag} --agentic'." >&2
            return 1
        fi
    fi

    # The MCP servers the agent talks to only exist while 'main' is up.
    # Refuse rather than launch an agent that would error on every tool call.
    if ! devenv-main-running "$instance"; then
        echo "start-coding-agent: instance '$instance' is not running." >&2
        echo "Start it first with './manage.sh run-devenv${ws_flag} --agentic'." >&2
        return 1
    fi

    # Per-client binary + config path (relative to the workspace dir so the
    # launch line in error messages and `exec` is short and stable). Codex has
    # no generated config file -- its servers are injected as `-c` flags below
    # -- so cfg_rel points at the committed template that those flags are built
    # from (present on ws0 in the repo, synced into ws1+).
    local bin cfg_rel
    case "$client" in
        claude)   bin="claude";   cfg_rel=".devenv/mcp/claude-code.json" ;;
        opencode) bin="opencode"; cfg_rel=".devenv/mcp/opencode.json" ;;
        vscode)   bin="code";     cfg_rel=".vscode/mcp.json" ;;
        codex)    bin="codex";    cfg_rel=".devenv/templates/codex.toml" ;;
    esac

    if ! command -v "$bin" >/dev/null 2>&1; then
        echo "start-coding-agent: '$bin' is not on PATH." >&2
        echo "Install it first, then retry." >&2
        return 1
    fi

    if [[ ! -f "$workspace/$cfg_rel" ]]; then
        echo "start-coding-agent: $workspace/$cfg_rel not found." >&2
        echo "Bring '$instance' up with './manage.sh run-devenv${ws_flag} --agentic'," >&2
        echo "which sets up the per-workspace MCP config." >&2
        return 1
    fi

    cd "$workspace" || return 1
    case "$client" in
        claude)
            exec claude --mcp-config "$cfg_rel" "$@"
            ;;
        opencode)
            OPENCODE_CONFIG="$cfg_rel" exec opencode "$@"
            ;;
        vscode)
            exec code "$workspace" "$@"
            ;;
        codex)
            # Build our servers as `-c mcp_servers.<name>....` overrides from the
            # committed templates (ports resolved from the instance number) and
            # pass them on the command line. Nothing is written to disk, so the
            # developer's project- or user-level .codex/config.toml is untouched.
            PENPOT_MCP_PORT=$(instance-port "$instance" "$PENPOT_PORT_BASE_MCP")
            SERENA_MCP_PORT=$(instance-port "$instance" "$PENPOT_PORT_BASE_SERENA")
            export PENPOT_MCP_PORT SERENA_MCP_PORT
            local -a codex_args=()
            local _assignment
            while IFS= read -r _assignment; do
                [[ -n "$_assignment" ]] && codex_args+=(-c "$_assignment")
            done < <(python3 .devenv/scripts/merge-mcp-config.py --format codex-args \
                        .devenv/shared/codex.toml \
                        .devenv/templates/codex.toml)
            if [[ ${#codex_args[@]} -eq 0 ]]; then
                echo "start-coding-agent: failed to build Codex MCP overrides from" >&2
                echo "  .devenv/{shared,templates}/codex.toml." >&2
                return 1
            fi
            exec codex "${codex_args[@]}" "$@"
            ;;
    esac
}

function build-imagemagick-docker-image {
    set +e;
    echo "Building image penpotapp/imagemagick:$IMAGEMAGICK_VERSION"

    pushd docker/imagemagick;

    output_option="type=registry";
    platform="linux/amd64,linux/arm64";

    if [ "$1" = "--local" ]; then
        output_option="type=docker";
        platform="linux/$ARCH"
    fi

    setup-buildx;

    docker buildx build \
      --build-arg IMAGEMAGICK_VERSION=$IMAGEMAGICK_VERSION \
      --platform $platform \
      --output $output_option \
      -t penpotapp/imagemagick:latest \
      -t penpotapp/imagemagick:$IMAGEMAGICK_VERSION .;

    popd;
}

function build {
    echo ">> build start: $1"
    local version=$(print-current-version);
    local script=${2:-build}

    pull-devenv-if-not-exists;
    docker volume create ${PENPOT_USER_DATA_VOLUME};
    docker run -t --rm \
           --mount source=${PENPOT_USER_DATA_VOLUME},type=volume,target=/home/penpot/ \
           --mount source=`pwd`,type=bind,target=/home/penpot/penpot \
           -e EXTERNAL_UID=$CURRENT_USER_ID \
           -e BUILD_STORYBOOK=$BUILD_STORYBOOK \
           -e BUILD_WASM=$BUILD_WASM \
           -e SHADOWCLJS_EXTRA_PARAMS=$SHADOWCLJS_EXTRA_PARAMS \
           -e JAVA_OPTS="$JAVA_OPTS" \
           -w /home/penpot/penpot/$1 \
           $DEVENV_IMGNAME:latest sudo -EH -u penpot ./scripts/$script $version

    echo ">> build end: $1"
}

function put-license-file {
    local target=$1;
    tee -a $target/LICENSE  >> /dev/null <<EOF
This Source Code Form is subject to the terms of the Mozilla Public
License, v. 2.0. If a copy of the MPL was not distributed with this
file, You can obtain one at http://mozilla.org/MPL/2.0/.

Copyright (c) KALEIDOS INC Sucursal en España SL
EOF
}

function build-frontend-bundle {
    echo ">> bundle frontend start";

    mkdir -p ./bundles
    local version=$(print-current-version);
    local bundle_dir="./bundles/frontend";

    build "frontend";

    rm -rf $bundle_dir;
    mv ./frontend/target/dist $bundle_dir;
    echo $version > $bundle_dir/version.txt;
    put-license-file $bundle_dir;
    echo ">> bundle frontend end";
}

function build-mcp-bundle {
    echo ">> bundle mcp start";

    mkdir -p ./bundles
    local version=$(print-current-version);
    local bundle_dir="./bundles/mcp";

    build "mcp";

    rm -rf $bundle_dir;
    mv ./mcp/dist $bundle_dir;
    echo $version > $bundle_dir/version.txt;
    put-license-file $bundle_dir;
    echo ">> bundle mcp end";
}


function build-backend-bundle {
    echo ">> bundle backend start";

    mkdir -p ./bundles
    local version=$(print-current-version);
    local bundle_dir="./bundles/backend";

    build "backend";

    rm -rf $bundle_dir;
    mv ./backend/target/dist $bundle_dir;
    echo $version > $bundle_dir/version.txt;
    put-license-file $bundle_dir;
    echo ">> bundle backend end";
}

function build-exporter-bundle {
    echo ">> bundle exporter start";

    mkdir -p ./bundles
    local version=$(print-current-version);
    local bundle_dir="./bundles/exporter";

    build "exporter";

    rm -rf $bundle_dir;
    mv ./exporter/target $bundle_dir;
    echo $version > $bundle_dir/version.txt
    put-license-file $bundle_dir;
    echo ">> bundle exporter end";
}

function build-storybook-bundle {
    echo ">> bundle storybook start";

    mkdir -p ./bundles
    local version=$(print-current-version);
    local bundle_dir="./bundles/storybook";

    build "frontend" "build-storybook";

    rm -rf $bundle_dir;
    mv ./frontend/storybook-static $bundle_dir;
    echo $version > $bundle_dir/version.txt;
    put-license-file $bundle_dir;
    echo ">> bundle storybook end";
}

function build-docs-bundle {
    echo ">> bundle docs start";

    mkdir -p ./bundles
    local version=$(print-current-version);
    local bundle_dir="./bundles/docs";

    build "docs";

    rm -rf $bundle_dir;
    mv ./docs/_dist $bundle_dir;
    echo $version > $bundle_dir/version.txt;
    put-license-file $bundle_dir;
    echo ">> bundle docs end";
}

function build-frontend-docker-image {
    rsync -avr --delete ./bundles/frontend/ ./docker/images/bundle-frontend/;
    pushd ./docker/images;
    docker build \
        -t penpotapp/frontend:$CURRENT_BRANCH -t penpotapp/frontend:latest \
        --build-arg BUNDLE_PATH="./bundle-frontend/" \
        -f Dockerfile.frontend .;
    popd;
}

function build-backend-docker-image {
    rsync -avr --delete ./bundles/backend/ ./docker/images/bundle-backend/;
    pushd ./docker/images;
    docker build \
        -t penpotapp/backend:$CURRENT_BRANCH -t penpotapp/backend:latest \
        --build-arg BUNDLE_PATH="./bundle-backend/" \
        -f Dockerfile.backend .;
    popd;
}

function build-exporter-docker-image {
    rsync -avr --delete ./bundles/exporter/ ./docker/images/bundle-exporter/;
    pushd ./docker/images;
    docker build \
        -t penpotapp/exporter:$CURRENT_BRANCH -t penpotapp/exporter:latest \
        --build-arg BUNDLE_PATH="./bundle-exporter/" \
        -f Dockerfile.exporter .;
    popd;
}

function build-mcp-docker-image {
    rsync -avr --delete ./bundles/mcp/ ./docker/images/bundle-mcp/;
    pushd ./docker/images;
    docker build \
        -t penpotapp/mcp:$CURRENT_BRANCH -t penpotapp/mcp:latest \
        --build-arg BUNDLE_PATH="./bundle-mcp/" \
        -f Dockerfile.mcp .;
    popd;
}

function build-storybook-docker-image {
    rsync -avr --delete ./bundles/storybook/ ./docker/images/bundle-storybook/;
    pushd ./docker/images;
    docker build \
        -t penpotapp/storybook:$CURRENT_BRANCH -t penpotapp/storybook:latest \
        --build-arg BUNDLE_PATH="./bundle-storybook/" \
        -f Dockerfile.storybook .;
    popd;
}

function usage {
    echo "PENPOT build & release manager"
    echo "USAGE: $0 OPTION"
    echo ""
    echo "Development environment (devenv)"
    echo "--------------------------------"
    echo "The devenv runs Penpot in a Docker container and supports parallel"
    echo "'workspaces': ws0 (the live repo at \$PWD) and optional wsN (N >= 1, sibling"
    echo "clones). Use --ws N to target a specific workspace; the default is 0."
    echo "Full guide: docs/technical-guide/developer/{devenv,agentic-devenv}.md."
    echo ""
    echo "Image lifecycle"
    echo "- pull-devenv                      Pull the devenv docker image from the registry."
    echo "- build-devenv [--local]           Build the devenv docker image (--local skips the registry push)."
    echo ""
    echo "Bring a devenv up / down"
    echo "- run-devenv                       Bring one workspace up, start its tmux session in the background,"
    echo "                                   and print the workspace's URLs. Pass --agentic to enable MCP + Serena"
    echo "                                   for AI-driven development (also regenerates per-workspace MCP configs)."
    echo "                                   Options:"
    echo "                                     --ws N                target workspace (default: 0)."
    echo "                                     --sync                re-seed the wsN clone from the live repo before"
    echo "                                                           starting (forbidden on ws0; implicit on first"
    echo "                                                           start of a wsN with no on-disk workspace yet)."
    echo "                                     --attach              attach to the tmux session after startup."
    echo "                                     --agentic             enable MCP + Serena (AI-agent mode)."
    echo "                                     --serena-context CTX  passed to Serena (default: desktop-app)."
    echo "                                     -e KEY=VAL            forwarded to 'docker exec' on attach."
    echo "                                     --git-user-name NAME / --git-user-email EMAIL"
    echo "                                                           identity wired into the container's git config"
    echo "                                                           (default: host's effective 'git config user.X',"
    echo "                                                           honouring per-repo local overrides; see"
    echo "                                                           devenv.md > 'Git identity inside the container')."
    echo "- create-devenv                    Create ws0 + shared-infra compose services without starting them."
    echo "- stop-devenv                      Stop one or more workspaces. Shared infra stops with the last one."
    echo "                                   Options: --ws N (stop wsN, N >= 1) | (no flag) (stop ws0 + infra;"
    echo "                                            refused if any wsN is still running) | --all (stop every wsN"
    echo "                                            highest first, then ws0 + infra)."
    echo "- drop-devenv                      Same CLI and invariants as stop-devenv (see above), plus removal of"
    echo "                                   the shared devenv image on full teardowns (no flag, --ws 0, or --all)."
    echo "                                   Refused if any wsN (N >= 1) is still running. A single --ws N (N >= 1)"
    echo "                                   keeps the image since other workspaces still need it."
    echo "- log-devenv                       Tail a workspace's compose logs."
    echo "                                   Options: --ws N (default: 0)."
    echo ""
    echo "Work inside a running devenv"
    echo "- attach-devenv                    Attach to the tmux session inside a running workspace."
    echo "                                   Options: --ws N (default: 0)."
    echo "- start-coding-agent <client>      Launch an AI coding agent against one workspace with the right MCP"
    echo "                                   config wired in. cd's into the workspace, refuses to launch if the"
    echo "                                   instance is not running, and forwards extra args to the client."
    echo "                                   client: claude | opencode | vscode | codex"
    echo "                                   Options: --ws N (default: 0). See agentic-devenv.md and"
    echo "                                   .devenv/README.md for per-client setup and override paths."
    echo ""
    echo "- build-bundle                     Build all bundles (frontend, backend, exporter, storybook and mcp)."
    echo "- build-frontend-bundle            Build frontend bundle"
    echo "- build-backend-bundle             Build backend bundle."
    echo "- build-exporter-bundle            Build exporter bundle."
    echo "- build-mcp-bundle                 Build mcp bundle."
    echo "- build-storybook-bundle           Build storybook bundle."
    echo "- build-docs-bundle                Build docs bundle."
    echo ""
    echo "- build-docker-images              Build all docker images (frontend, backend, exporter, mcp and storybook)."
    echo "- build-frontend-docker-image      Build frontend docker images."
    echo "- build-backend-docker-image       Build backend docker images."
    echo "- build-exporter-docker-image      Build exporter docker images."
    echo "- build-mcp-docker-image           Build exporter docker images."
    echo "- build-storybook-docker-image     Build storybook docker images."
    echo "- build-imagemagick-docker-image   Build imagemagic docker images."
    echo ""
    echo "- version                          Show penpot's version."
}

case $1 in
    version)
        print-current-version
        ;;

    ## devenv related commands
    pull-devenv)
        pull-devenv ${@:2};
        ;;

    build-devenv)
        shift;
        build-devenv $@;
        ;;

    create-devenv)
        create-devenv ${@:2}
        ;;

    run-devenv)
        run-devenv ${@:2}
        ;;
    run-devenv-agentic)
        run-devenv --agentic ${@:2}
        ;;
    attach-devenv)
        attach-devenv ${@:2}
        ;;
    start-coding-agent)
        start-coding-agent "${@:2}"
        ;;
    stop-devenv)
        stop-devenv ${@:2}
        ;;
    drop-devenv)
        drop-devenv ${@:2}
        ;;
    log-devenv)
        log-devenv ${@:2}
        ;;

    ## production builds
    build-bundle)
        build-frontend-bundle;
        build-mcp-bundle;
        build-backend-bundle;
        build-exporter-bundle;
        build-storybook-bundle;
        ;;

    build-frontend-bundle)
        build-frontend-bundle;
        ;;

    build-mcp-bundle)
        build-mcp-bundle;
        ;;

    build-backend-bundle)
        build-backend-bundle;
        ;;

    build-exporter-bundle)
        build-exporter-bundle;
        ;;

    build-storybook-bundle)
        build-storybook-bundle;
        ;;

    build-docs-bundle)
        build-docs-bundle;
        ;;

    build-docker-images)
        build-frontend-docker-image
        build-backend-docker-image
        build-exporter-docker-image
        build-mcp-docker-image
        build-storybook-docker-image
        ;;

    build-frontend-docker-image)
        build-frontend-docker-image
        ;;

    build-backend-docker-image)
        build-backend-docker-image
        ;;

    build-exporter-docker-image)
        build-exporter-docker-image
        ;;

    build-mcp-docker-image)
        build-mcp-docker-image
        ;;

    build-storybook-docker-image)
        build-storybook-docker-image
        ;;

    build-imagemagick-docker-image)
        shift;
        build-imagemagick-docker-image $@;
        ;;

    *)
        usage
        ;;
esac
