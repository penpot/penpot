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

# Per-instance values like PENPOT_REDIS_URI must live in each instance's env
# file (not in this shell), because docker compose's --env-file mechanism
# lets a per-instance overlay override the baseline while the shell env
# would otherwise shadow both for every project.

export CURRENT_USER_ID=$(id -u);
export CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD);

export IMAGEMAGICK_VERSION=7.1.2-13

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
#   devenv-compose          wrap 'docker compose' with --env-file + both files
#   devenv-main-container   resolve the 'main' container id via compose ps
#   devenv-main-running     true if 'main' is up
#
# Devenv lifecycle (operate on the whole compose project)
#   start-devenv, create-devenv, stop-devenv, drop-devenv, log-devenv
#
# Devenv interactive entry points (all operate on the running 'main' container)
#   run-devenv-tmux         starts 'main' if needed and execs start-tmux.sh
#                           interactively (this is what 'run-devenv' resolves to)
#   run-devenv-agentic      same as run-devenv-tmux but enables MCP + Serena
#   attach-devenv           pure attach to the existing tmux session; fails
#                           fast if the devenv or session is missing
#   run-devenv-shell        starts 'main' if needed and execs a bash shell
#   run-devenv-isolated-shell  one-shot 'docker run' (NOT compose) against the
#                           project user_data volume and the current PWD; used
#                           for ad-hoc operations that should not touch a
#                           running devenv
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
# - Each runtime instance (ws0, ws1, ...) runs its own main + valkey under
#   project `penpotdev-wsN`. ws0 uses only `defaults.env`; ws1+ additionally
#   layer a generated overlay file under `docker/devenv/instances/`.
# `env -i` strips the shell env before invoking docker compose so the
# per-instance overlay --env-file actually overrides defaults.env. Without
# stripping, the shell would still hold whatever values defaults.env was
# sourced into at startup (PENPOT_MAIN_CONTAINER_NAME, etc.), and Docker
# Compose's substitution gives the shell precedence over --env-file.
# Only the values that genuinely need to be per-call (HOME/PATH for tooling,
# CURRENT_USER_ID/PENPOT_SOURCE_PATH for the compose substitution) are
# re-exported.
function infra-compose {
    env -i HOME="$HOME" PATH="$PATH" PWD="$PWD" \
        docker compose -p penpotdev-infra \
            --env-file "$DEVENV_DEFAULTS_FILE" \
            -f docker/devenv/docker-compose.infra.yml \
            "$@"
}

function instance-compose {
    local instance="$1"; shift
    local source_path env_files
    env_files=(--env-file "$DEVENV_DEFAULTS_FILE")
    if [[ "$instance" == "ws0" ]]; then
        source_path="$PWD"
    else
        source_path="$(workspace-path "$instance")"
        env_files+=(--env-file "docker/devenv/instances/${instance}.env")
    fi
    env -i HOME="$HOME" PATH="$PATH" PWD="$PWD" \
        CURRENT_USER_ID="${CURRENT_USER_ID:-$(id -u)}" \
        PENPOT_SOURCE_PATH="$source_path" \
        docker compose -p "penpotdev-${instance}" \
            "${env_files[@]}" \
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
    instance-compose "$instance" ps -q main
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
    echo "$HOME/.penpot/penpot_workspaces/$instance"
}

# Generate (or refresh) the per-instance Compose env-file overlay. Idempotent;
# safe to call on every reconciler pass.
function write-instance-env {
    local instance="$1"
    if [[ "$instance" == "ws0" ]]; then
        return 0
    fi

    if [[ ! "$instance" =~ ^ws([0-9]+)$ ]]; then
        echo "write-instance-env: invalid instance '$instance'" >&2
        return 1
    fi
    local n="${BASH_REMATCH[1]}"
    local offset=$(( n * 10000 ))

    local file="docker/devenv/instances/${instance}.env"
    mkdir -p docker/devenv/instances
    local workspace
    workspace=$(workspace-path "$instance")
    cat >"$file" <<EOF
# Auto-generated by manage.sh for instance '$instance'.
# Edits are overwritten on the next reconciler pass.

COMPOSE_PROJECT_NAME=penpotdev-${instance}
PENPOT_MAIN_CONTAINER_NAME=penpot-devenv-${instance}-main
PENPOT_VALKEY_CONTAINER_NAME=penpot-devenv-${instance}-valkey
PENPOT_VALKEY_HOSTNAME=penpot-devenv-${instance}-valkey
PENPOT_USER_DATA_VOLUME=penpotdev_${instance}_user_data
PENPOT_VALKEY_DATA_VOLUME=penpotdev_${instance}_valkey_data

PENPOT_PUBLIC_URI=https://localhost:$(( 3449 + offset ))
PENPOT_REDIS_URI=redis://penpot-devenv-${instance}-valkey/0
PENPOT_TMUX_SESSION=penpot

PENPOT_PUBLIC_HTTP_PORT=$(( 3449 + offset ))
PENPOT_MCP_SERVER_PORT=$(( 4401 + offset ))
PENPOT_MCP_REPL_PORT=$(( 4403 + offset ))
SERENA_EXTERNAL_PORT=$(( 14281 + offset ))
SERENA_DASHBOARD_EXTERNAL_PORT=$(( 14282 + offset ))

# Background workers run only on ws0 to keep async-task notifications bound
# to a single Valkey Pub/Sub. See mem:devenv/core.
PENPOT_BACKEND_WORKER=false

# Workspace bind mount (computed in manage.sh too, but recorded here for
# clarity when inspecting the env file).
PENPOT_SOURCE_PATH=${workspace}
EOF
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

    (
        cd "$workspace"
        git switch -C "${instance}/${CURRENT_BRANCH}" >/dev/null
    )
}

function start-devenv {
    pull-devenv-if-not-exists $@;
    ensure-devenv-network;

    ensure-infra-up
    instance-compose ws0 up -d
}

function create-devenv {
    pull-devenv-if-not-exists $@;
    ensure-devenv-network;

    infra-compose create
    instance-compose ws0 create
}

function stop-devenv {
    local ws
    for ws in $(list-running-instances); do
        instance-compose "$ws" stop -t 2
    done
    infra-compose stop -t 2
}

function drop-devenv {
    local ws
    for ws in $(list-running-instances); do
        # Never -v: data preservation rule.
        instance-compose "$ws" down -t 2
    done
    infra-compose down -t 2

    echo "Clean old development image $DEVENV_IMGNAME..."
    docker images $DEVENV_IMGNAME -q | xargs --no-run-if-empty docker rmi
}

function log-devenv {
    # Tail ws0 by default; for multi-instance dev, attach explicitly per project.
    instance-compose ws0 logs -f --tail=50
}

function run-devenv-tmux {
    local extra_env_args=()
    local instance="ws0"

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --instance)
                instance="$(normalize-instance "$2")"; shift 2;;
            -e)
                extra_env_args+=(-e "$2"); shift 2;;
            -e*)
                extra_env_args+=(-e "${1#-e}"); shift;;
            *)
                echo "run-devenv: unknown argument '$1'" >&2
                return 1;;
        esac
    done

    if ! devenv-main-running "$instance"; then
        if [[ "$instance" == "ws0" ]]; then
            start-devenv
            echo "Waiting for containers fully start (5s)..."
            sleep 5
        else
            echo "Instance '$instance' is not running; bring it up first with './manage.sh run-devenv-agentic --n-instances N'." >&2
            return 1
        fi
    fi

    local container
    container=$(devenv-main-container "$instance")
    docker exec -ti \
        "${extra_env_args[@]}" \
        "$container" sudo -EH -u penpot PENPOT_PLUGIN_DEV=$PENPOT_PLUGIN_DEV /home/start-tmux.sh
}

# Normalize an instance specifier ("0", "ws0", "1", "ws3", ...) to "wsN".
function normalize-instance {
    local raw="$1"
    if [[ "$raw" =~ ^ws[0-9]+$ ]]; then
        echo "$raw"
    elif [[ "$raw" =~ ^[0-9]+$ ]]; then
        echo "ws$raw"
    else
        echo "Invalid --instance value: '$raw' (expected 0|ws0|1|ws1|...)" >&2
        return 1
    fi
}


# Bring a single instance up: workspace sync (skipped for ws0), env-file
# write (skipped for ws0), compose up, and detached tmux start with the
# requested feature flags.
function start-instance {
    local instance="$1"
    local enable_mcp="$2"
    local enable_serena="$3"
    local serena_context="$4"

    if [[ "$instance" != "ws0" ]]; then
        sync-workspace "$instance"
        write-instance-env "$instance"
    fi

    instance-compose "$instance" up -d --no-deps main redis

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

    # Start the tmux session detached so the reconciler can proceed to the
    # next instance without blocking on an interactive attach.
    local tmux_env=(-e PENPOT_TMUX_ATTACH=false)
    if [[ "$enable_mcp" == "true" ]]; then
        tmux_env+=(-e PENPOT_FLAGS="${PENPOT_FLAGS:-} enable-mcp")
    fi
    if [[ "$enable_serena" == "true" ]]; then
        tmux_env+=(-e SERENA_ENABLED=true -e SERENA_CONTEXT="$serena_context")
    fi
    docker exec -d "${tmux_env[@]}" "$container" \
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
    local enable_mcp="$2"
    local enable_serena="$3"
    local n=0
    [[ "$instance" =~ ^ws([0-9]+)$ ]] && n="${BASH_REMATCH[1]}"
    local offset=$(( n * 10000 ))
    local public=$(( 3449 + offset ))
    local mcp=$(( 4401 + offset ))
    local serena=$(( 14281 + offset ))

    echo
    echo "[$instance]"
    echo "  Penpot UI:           https://localhost:${public}"
    if [[ "$enable_mcp" == "true" ]]; then
        echo "  MCP stream:          http://localhost:${mcp}/mcp"
    fi
    if [[ "$enable_serena" == "true" ]]; then
        echo "  Serena MCP:          http://localhost:${serena}"
    fi
    echo "  Attach:              ./manage.sh attach-devenv --instance ${instance}"
}

# Reconcile the running parallel set to exactly {ws0..ws(N-1)}.
function run-devenv-agentic {
    local n_instances=1
    local enable_mcp=true
    local enable_serena=true
    local serena_context="desktop-app"

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --n-instances)
                n_instances="$2"; shift 2;;
            --serena-context)
                serena_context="$2"; shift 2;;
            --no-mcp)
                enable_mcp=false; shift;;
            --no-serena)
                enable_serena=false; shift;;
            *)
                echo "run-devenv-agentic: unknown argument '$1'" >&2
                return 1;;
        esac
    done

    if ! [[ "$n_instances" =~ ^[1-9][0-9]*$ ]]; then
        echo "run-devenv-agentic: --n-instances must be a positive integer (got '$n_instances')" >&2
        return 1
    fi

    pull-devenv-if-not-exists
    ensure-devenv-network
    ensure-infra-up

    # Compute target and running sets.
    local target=()
    local i
    for (( i=0; i < n_instances; i++ )); do
        target+=("ws$i")
    done
    local running
    running=$(list-running-instances)

    # Stop extras, highest-numbered first.
    local to_stop=()
    for ws in $running; do
        if ! printf '%s\n' "${target[@]}" | grep -qx "$ws"; then
            to_stop+=("$ws")
        fi
    done
    if [[ ${#to_stop[@]} -gt 0 ]]; then
        # Sort numerically descending.
        IFS=$'\n' to_stop=($(printf '%s\n' "${to_stop[@]}" \
            | sed 's/^ws//' | sort -rn | sed 's/^/ws/'))
        unset IFS
        for ws in "${to_stop[@]}"; do
            echo "Stopping $ws..."
            stop-instance "$ws"
        done
    fi

    # Start missing instances.
    for ws in "${target[@]}"; do
        if printf '%s\n' "$running" | grep -qx "$ws"; then
            echo "[$ws] already running; leaving alone"
            continue
        fi
        echo "Starting $ws..."
        start-instance "$ws" "$enable_mcp" "$enable_serena" "$serena_context"
    done

    # Per-instance startup info.
    for ws in "${target[@]}"; do
        print-instance-info "$ws" "$enable_mcp" "$enable_serena"
    done
}

function run-devenv-shell {
    local instance="ws0"
    local positional=()
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --instance)
                instance="$(normalize-instance "$2")"; shift 2;;
            *)
                positional+=("$1"); shift;;
        esac
    done

    if ! devenv-main-running "$instance"; then
        if [[ "$instance" == "ws0" ]]; then
            start-devenv
        else
            echo "Instance '$instance' is not running." >&2
            return 1
        fi
    fi
    local container
    container=$(devenv-main-container "$instance")
    docker exec -ti \
           -e JAVA_OPTS="$JAVA_OPTS" \
           -e EXTERNAL_UID=$CURRENT_USER_ID \
           "$container" sudo -EH -u penpot "${positional[@]}"
}

function attach-devenv {
    local instance="ws0"
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --instance)
                instance="$(normalize-instance "$2")"; shift 2;;
            *)
                echo "attach-devenv: unknown argument '$1'" >&2
                return 1;;
        esac
    done

    if ! devenv-main-running "$instance"; then
        echo "Instance '$instance' is not running." >&2
        echo "Start it first with './manage.sh run-devenv' (ws0) or './manage.sh run-devenv-agentic --n-instances N' (parallel)." >&2
        return 1
    fi

    local session="${PENPOT_TMUX_SESSION:-penpot}"
    local container
    container=$(devenv-main-container "$instance")

    if ! docker exec "$container" sudo -EH -u penpot tmux has-session -t "$session" 2>/dev/null; then
        echo "No tmux session '$session' inside instance '$instance'." >&2
        echo "Start it with './manage.sh run-devenv' (ws0) or './manage.sh run-devenv-agentic'." >&2
        return 1
    fi

    docker exec -ti "$container" sudo -EH -u penpot tmux attach -t "$session"
}

function run-devenv-isolated-shell {
    docker volume create ${PENPOT_USER_DATA_VOLUME};
    docker run -ti --rm \
           --mount source=${PENPOT_USER_DATA_VOLUME},type=volume,target=/home/penpot/ \
           --mount source=`pwd`,type=bind,target=/home/penpot/penpot \
           -e EXTERNAL_UID=$CURRENT_USER_ID \
           -e BUILD_STORYBOOK=$BUILD_STORYBOOK \
           -e BUILD_WASM=$BUILD_WASM \
           -e SHADOWCLJS_EXTRA_PARAMS=$SHADOWCLJS_EXTRA_PARAMS \
           -e JAVA_OPTS="$JAVA_OPTS" \
           -w /home/penpot/penpot/$1 \
           $DEVENV_IMGNAME:latest sudo -EH -u penpot $@
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

Copyright (c) KALEIDOS INC
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
    echo "Options:"
    echo "- pull-devenv                      Pulls docker development oriented image"
    echo "- build-devenv                     Build docker development oriented image"
    echo "- build-devenv --local             Build a local docker development oriented image"
    echo "- create-devenv                    Create the development oriented docker compose service."
    echo "- start-devenv                     Start the development oriented docker compose service."
    echo "- stop-devenv                      Stops the development oriented docker compose service."
    echo "- drop-devenv                      Remove the development oriented docker compose containers, volumes and clean images."
    echo "- run-devenv                       Brings ws0 up and attaches to its tmux session (no MCP, no Serena)."
    echo "                                   Optional --instance <wsN> targets a different instance."
    echo "                                   Optional -e flags are forwarded to 'docker exec' (e.g. -e MY_VAR=value)."
    echo "- run-devenv-agentic               Desired-state reconciler. Brings the running parallel set to exactly"
    echo "                                   {ws0..ws(N-1)} with MCP and Serena enabled on each."
    echo "                                   Options: --n-instances N (default: 1), --serena-context CONTEXT (default: desktop-app),"
    echo "                                            --no-mcp, --no-serena"
    echo "- attach-devenv                    Attaches to the tmux session inside a running instance."
    echo "                                   Options: --instance 0|wsN|N (default: 0)"
    echo "- run-devenv-shell                 Opens a bash shell inside a running instance."
    echo "                                   Options: --instance 0|wsN|N (default: 0)"
    echo "- isolated-shell                   Starts a bash shell in a new devenv container."
    echo "- log-devenv                       Show logs of the running devenv docker compose service."
    echo ""
    echo "- build-bundle                     Build all bundles (frontend, backend, exporter, storybook and mcp)."
    echo "- build-frontend-bundle            Build frontend bundle"
    echo "- build-backend-bundle             Build backend bundle."
    echo "- build-exporter-bundle            Build exporter bundle."
    echo "- build-storybook-bundle           Build storybook bundle."
    echo "- build-mcp-bundle                 Build mcp bundle."
    echo "- build-docs-bundle                Build docs bundle."
    echo ""
    echo "- build-docker-images              Build all docker images (frontend, backend and exporter)."
    echo "- build-frontend-docker-image      Build frontend docker images."
    echo "- build-backend-docker-image       Build backend docker images."
    echo "- build-exporter-docker-image      Build exporter docker images."
    echo "- build-mcp-docker-image           Build exporter docker images."
    echo "- build-storybook-docker-image     Build storybook docker images."
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

    start-devenv)
        start-devenv ${@:2}
        ;;
    run-devenv)
        run-devenv-tmux ${@:2}
        ;;
    run-devenv-agentic)
        run-devenv-agentic ${@:2}
        ;;
    attach-devenv)
        attach-devenv ${@:2}
        ;;
    run-devenv-shell)
        run-devenv-shell ${@:2}
        ;;

    isolated-shell)
        run-devenv-isolated-shell ${@:2}
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

    build-imagemagick-docker-image)
        shift;
        build-imagemagick-docker-image $@;
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

    *)
        usage
        ;;
esac
