#!/usr/bin/env bash

export ORGANIZATION="penpotapp";
export DEVENV_IMGNAME="$ORGANIZATION/devenv";
export DEVENV_PNAME="penpotdev";

export CURRENT_USER_ID=$(id -u);
export CURRENT_VERSION=$(cat ./version.txt);
export CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD);
export CURRENT_HASH=$(git rev-parse --short HEAD);
export CURRENT_COMMITS=$(git rev-list --count HEAD)

set -ex

function print-current-version {
    echo -n "$CURRENT_VERSION-$CURRENT_COMMITS-g$CURRENT_HASH"
}

function build-devenv {
    set +e;
    echo "Building development image $DEVENV_IMGNAME:latest..."

    pushd docker/devenv;

    docker run --privileged --rm tonistiigi/binfmt --install all
    docker buildx inspect penpot > /dev/null 2>&1;

    if [ $? -eq 1 ]; then
        docker buildx create --name=penpot --use
        docker buildx inspect --bootstrap > /dev/null 2>&1;
    else
        docker buildx use penpot;
        docker buildx inspect --bootstrap  > /dev/null 2>&1;
    fi

    # docker build -t $DEVENV_IMGNAME:latest .
    docker buildx build --platform linux/amd64,linux/arm64 --push -t $DEVENV_IMGNAME:latest .;
    docker pull $DEVENV_IMGNAME:latest;

    popd;
}

function build-devenv-local {
    echo "Building local only development image $DEVENV_IMGNAME:latest..."

    pushd docker/devenv;
    docker build -t $DEVENV_IMGNAME:latest .;
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

function start-devenv {
    pull-devenv-if-not-exists $@;

    docker compose -p $DEVENV_PNAME -f docker/devenv/docker-compose.yaml up -d;
}

function stop-devenv {
    docker compose -p $DEVENV_PNAME -f docker/devenv/docker-compose.yaml stop -t 2;
}

function drop-devenv {
    docker compose -p $DEVENV_PNAME -f docker/devenv/docker-compose.yaml down -t 2 -v;

    echo "Clean old development image $DEVENV_IMGNAME..."
    docker images $DEVENV_IMGNAME -q | awk '{print $3}' | xargs --no-run-if-empty docker rmi
}

function log-devenv {
    docker compose -p $DEVENV_PNAME -f docker/devenv/docker-compose.yaml logs -f --tail=50
}

function run-devenv-tmux {
    if [[ ! $(docker ps -f "name=penpot-devenv-main" -q) ]]; then
        start-devenv
    fi

    docker exec -ti penpot-devenv-main sudo -EH -u penpot PENPOT_PLUGIN_DEV=$PENPOT_PLUGIN_DEV /home/start-tmux.sh
}

function run-devenv-shell {
    if [[ ! $(docker ps -f "name=penpot-devenv-main" -q) ]]; then
        start-devenv
    fi
    docker exec -ti penpot-devenv-main sudo -EH -u penpot bash
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

function build-docker-images {
    echo ">> docker frontend image build start";
    docker build -t penpotapp/frontend:$CURRENT_BRANCH -t penpotapp/frontend:latest -f ./docker/images/Dockerfile.frontend .;
    echo ">> docker frontend image build end";

    echo ">> docker backend image build start";
    docker build -t penpotapp/backend:$CURRENT_BRANCH -t penpotapp/backend:latest -f ./docker/images/Dockerfile.backend .;
    echo ">> docker backend image build end";

    echo ">> docker exporter image build start";
    docker build -t penpotapp/exporter:$CURRENT_BRANCH -t penpotapp/exporter:latest -f ./docker/images/Dockerfile.exporter .;
    echo ">> docker exporter image build end";
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
    echo ""
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
        build-devenv ${@:2}
        ;;

    build-devenv-local)
        build-devenv-local ${@:2}
        ;;

    push-devenv)
        push-devenv ${@:2}
        ;;

    start-devenv)
        start-devenv ${@:2}
        ;;
    run-devenv)
        run-devenv-tmux ${@:2}
        ;;
    run-devenv-shell)
        run-devenv-shell ${@:2}
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

    # production builds internal tooling
    put-license-file)
        put-license-file ${@:2}
        ;;

    # production builds
    build-docker-images)
        build-docker-images
        ;;

    # Docker Image Tasks
    *)
        usage
        ;;
esac
