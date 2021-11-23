#!/usr/bin/env bash
set -e

export ORGANIZATION="penpotapp";
export DEVENV_IMGNAME="$ORGANIZATION/devenv";
export DEVENV_PNAME="penpotdev";

export CURRENT_USER_ID=$(id -u);
export CURRENT_VERSION=$(cat ./version.txt);
export CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD);
export CURRENT_HASH=$(git rev-parse --short HEAD);
export CURRENT_COMMITS=$(git rev-list --count HEAD)

function print-current-version {
    if [ $CURRENT_BRANCH != "main" ]; then
        echo -n "$CURRENT_BRANCH-$CURRENT_VERSION-$CURRENT_COMMITS-g$CURRENT_HASH"
    else
        echo -n "$CURRENT_VERSION-$CURRENT_COMMITS-g$CURRENT_HASH"
    fi
}

function build-devenv {
    echo "Building development image $DEVENV_IMGNAME:latest..."

    pushd docker/devenv;
    docker build -t $DEVENV_IMGNAME:latest .
    popd;
}

function push-devenv {
    docker push $DEVENV_IMGNAME:latest
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

function start-devenv {
    pull-devenv-if-not-exists $@;

    # Check if the "backend-only" container is running. If it is, we need tot stop it first
    if [[ ! $(docker ps -f "name=penpot-backend" -q) ]]; then
        docker compose -p $DEVENV_PNAME --profile backend -f docker/devenv/docker-compose.yaml stop -t 2 backend;
    fi

    docker compose -p $DEVENV_PNAME --profile full -f docker/devenv/docker-compose.yaml up -d;
}

function start-backend {
    pull-devenv-if-not-exists $@;

    # Check if the "devenv" container is running. If it is, we need tot stop it first because conflicts with the backend
    if [[ ! $(docker ps -f "name=penpot-devenv-main" -q) ]]; then
        docker compose -p $DEVENV_PNAME --profile full -f docker/devenv/docker-compose.yaml stop -t 2 main;
    fi

    docker compose -p $DEVENV_PNAME --profile backend -f docker/devenv/docker-compose.yaml up -d;
}

function stop-devenv {
    docker compose -p $DEVENV_PNAME --profile full --profile backend -f docker/devenv/docker-compose.yaml stop -t 2;
}

function drop-devenv {
    docker compose -p $DEVENV_PNAME --profile full --profile backend -f docker/devenv/docker-compose.yaml down -t 2 -v;

    echo "Clean old development image $DEVENV_IMGNAME..."
    docker images $DEVENV_IMGNAME -q | awk '{print $3}' | xargs --no-run-if-empty docker rmi
}

function log-devenv {
    docker compose -p $DEVENV_PNAME -f docker/devenv/docker-compose.yaml logs -f --tail=50
}

function run-devenv {
    if [[ ! $(docker ps -f "name=penpot-devenv-main" -q) ]]; then
        start-devenv
    fi

    docker exec -ti penpot-devenv-main sudo -EH -u penpot /home/start-tmux.sh
}

function run-backend {
    if [[ ! $(docker ps -f "name=penpot-backend" -q) ]]; then
        start-backend
    fi

    docker exec -ti penpot-backend sudo -EH -u penpot /home/start-tmux-back.sh
}

function build {
    echo ">> build start: $1"
    local version=$(print-current-version);

    pull-devenv-if-not-exists;
    docker volume create ${DEVENV_PNAME}_user_data;
    docker run -t --rm \
           --mount source=${DEVENV_PNAME}_user_data,type=volume,target=/home/penpot/ \
           --mount source=`pwd`,type=bind,target=/home/penpot/penpot \
           -e EXTERNAL_UID=$CURRENT_USER_ID \
           -e SHADOWCLJS_EXTRA_PARAMS=$SHADOWCLJS_EXTRA_PARAMS \
           -w /home/penpot/penpot/$1 \
           $DEVENV_IMGNAME:latest sudo -EH -u penpot ./scripts/build $version

    echo ">> build end: $1"
}

function put-license-file {
    local target=$1;
    tee -a $target/LICENSE  >> /dev/null <<EOF
This Source Code Form is subject to the terms of the Mozilla Public
License, v. 2.0. If a copy of the MPL was not distributed with this
file, You can obtain one at http://mozilla.org/MPL/2.0/.

Copyright (c) UXBOX Labs SL
EOF
}

function build-frontend-bundle {
    echo ">> bundle frontend start";

    local version=$(print-current-version);
    local bundle_dir="./bundle-frontend";

    build "frontend";

    rm -rf $bundle_dir;
    mv ./frontend/target/dist $bundle_dir;
    echo $version > $bundle_dir/version.txt;
    put-license-file $bundle_dir;
    echo ">> bundle frontend end";
}

function build-backend-bundle {
    echo ">> bundle backend start";

    local version=$(print-current-version);
    local bundle_dir="./bundle-backend";

    build "backend";

    rm -rf $bundle_dir;
    mv ./backend/target/dist $bundle_dir;
    echo $version > $bundle_dir/version.txt;
    put-license-file $bundle_dir;
    echo ">> bundle frontend end";
}

function build-exporter-bundle {
    echo ">> bundle exporter start";
    local version=$(print-current-version);
    local bundle_dir="./bundle-exporter";

    build "exporter";

    rm -rf $bundle_dir;
    mv ./exporter/target $bundle_dir;

    echo $version > $bundle_dir/version.txt
    put-license-file $bundle_dir;

    echo ">> bundle exporter end";
}

# DEPRECATED: temporary maintained for backward compatibility.

function build-app-bundle {
    echo ">> bundle app start";

    local version=$(print-current-version);
    local bundle_dir="./bundle-app";

    build "frontend";
    build "backend";

    rm -rf $bundle_dir
    mkdir -p $bundle_dir;
    mv ./frontend/target/dist $bundle_dir/frontend;
    mv ./backend/target/dist $bundle_dir/backend;

    echo $version > $bundle_dir/version.txt
    put-license-file $bundle_dir;
    echo ">> bundle app end";
}

function usage {
    echo "PENPOT build & release manager"
    echo "USAGE: $0 OPTION"
    echo "Options:"
    echo "- pull-devenv                      Pulls docker development oriented image"
    echo "- build-devenv                     Build docker development oriented image"
    echo "- start-devenv                     Start the development oriented docker compose service."
    echo "- stop-devenv                      Stops the development oriented docker compose service."
    echo "- drop-devenv                      Remove the development oriented docker compose containers, volumes and clean images."
    echo "- run-devenv                       Attaches to the running devenv container and starts development environment"
    echo "- start-backend                    Start the backend only service."
    echo "- run-backend                      Starts a backend-only instance and attach tmux to it"
    echo "                                   based on tmux (frontend at localhost:3449, backend at localhost:6060)."
    echo ""
}

case $1 in
    ## devenv related commands
    pull-devenv)
        pull-devenv ${@:2};
        ;;

    build-devenv)
        build-devenv ${@:2}
        ;;

    push-devenv)
        push-devenv ${@:2}
        ;;

    start-devenv)
        start-devenv ${@:2}
        ;;
    start-backend)
        start-backend ${@:2}
        ;;
    run-devenv)
        run-devenv ${@:2}
        ;;
    run-backend)
        run-backend ${@:2}
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

    # production builds
    build-app-bundle)
        build-app-bundle;
        ;;

    build-frontend-bundle)
        build-frontend-bundle;
        ;;

    build-backend-bundle)
        build-backend-bundle;
        ;;

    build-exporter-bundle)
        build-exporter-bundle;
        ;;

    # Docker Image Tasks
    *)
        usage
        ;;
esac
