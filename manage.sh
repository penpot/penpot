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
export PENPOT_SOURCE_PATH="${PENPOT_SOURCE_PATH:-$PWD}"

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

function devenv-compose {
    docker compose \
        --env-file "$DEVENV_DEFAULTS_FILE" \
        -f docker/devenv/docker-compose.infra.yml \
        -f docker/devenv/docker-compose.main.yml \
        "$@"
}

function devenv-main-container {
    devenv-compose ps -q main
}

function devenv-main-running {
    local container=$(devenv-main-container)
    [[ -n "$container" ]] && [[ "$(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null)" = "true" ]]
}

function start-devenv {
    pull-devenv-if-not-exists $@;
    ensure-devenv-network;

    devenv-compose up -d;
}

function create-devenv {
    pull-devenv-if-not-exists $@;
    ensure-devenv-network;

    devenv-compose create;
}

function stop-devenv {
    devenv-compose stop -t 2;
}

function drop-devenv {
    devenv-compose down -t 2 -v;

    echo "Clean old development image $DEVENV_IMGNAME..."
    docker images $DEVENV_IMGNAME -q | awk '{print $3}' | xargs --no-run-if-empty docker rmi
}

function log-devenv {
    devenv-compose logs -f --tail=50
}

function run-devenv-tmux {
    local extra_env_args=()

    while [[ $# -gt 0 ]]; do
        case "$1" in
            -e)
                extra_env_args+=(-e "$2"); shift 2;;
            -e*)
                extra_env_args+=(-e "${1#-e}"); shift;;
            *)
                echo "run-devenv: unknown argument '$1'" >&2
                return 1;;
        esac
    done

    if ! devenv-main-running; then
        start-devenv
        echo "Waiting for containers fully start (5s)..."
        sleep 5;
    fi

    local container=$(devenv-main-container)
    docker exec -ti \
        "${extra_env_args[@]}" \
        "$container" sudo -EH -u penpot PENPOT_PLUGIN_DEV=$PENPOT_PLUGIN_DEV /home/start-tmux.sh
}


function run-devenv-agentic {
    local serena_context="desktop-app"

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --serena-context)
                serena_context="$2"; shift 2;;
            *)
                echo "run-devenv-agentic: unknown argument '$1'" >&2
                return 1;;
        esac
    done

    if ! devenv-main-running; then
        start-devenv
        echo "Waiting for containers fully start (5s)..."
        sleep 5;
    fi

    run-devenv-tmux \
        -e SERENA_ENABLED=true \
        -e SERENA_CONTEXT="$serena_context" \
        -e PENPOT_FLAGS="${PENPOT_FLAGS} enable-mcp"
}

function run-devenv-shell {
    if ! devenv-main-running; then
        start-devenv
    fi
    local container=$(devenv-main-container)
    docker exec -ti \
           -e JAVA_OPTS="$JAVA_OPTS" \
           -e EXTERNAL_UID=$CURRENT_USER_ID \
           "$container" sudo -EH -u penpot $@
}

function attach-devenv {
    if ! devenv-main-running; then
        echo "devenv is not running." >&2
        echo "Start it first with './manage.sh run-devenv' (or './manage.sh start-devenv' for containers only)." >&2
        return 1
    fi

    local session="${PENPOT_TMUX_SESSION:-penpot}"
    local container=$(devenv-main-container)

    if ! docker exec "$container" sudo -EH -u penpot tmux has-session -t "$session" 2>/dev/null; then
        echo "No tmux session '$session' inside the devenv container." >&2
        echo "Start it with './manage.sh run-devenv'." >&2
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
    echo "- run-devenv                       Attaches to the running devenv container and starts development environment"
    echo "                                   Optional -e flags are forwarded to 'docker exec' (e.g. -e MY_VAR=value)."
    echo "- run-devenv-agentic               Like run-devenv but with additional processes for agentic development enabled."
    echo "                                   Options: --serena-context CONTEXT (default: desktop-app)"
    echo "- attach-devenv                    Attaches to the tmux session inside the running devenv container."
    echo "- run-devenv-shell                 Attaches to the running devenv container and starts a bash shell."
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
