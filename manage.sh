#!/usr/bin/env bash
set -e

REV=`git log -n 1 --pretty=format:%h -- docker/`
DEVENV_IMGNAME="uxbox-devenv"

function build-devenv {
    echo "Building development image $DEVENV_IMGNAME:latest with UID $EXTERNAL_UID..."

    local EXTERNAL_UID=${1:-$(id -u)}

    docker build --rm=true --force-rm \
           -t $DEVENV_IMGNAME:latest \
           --build-arg EXTERNAL_UID=$EXTERNAL_UID \
           docker/devenv/;
}

function build-devenv-if-not-exists {
    if [[ ! $(docker images $DEVENV_IMGNAME:latest -q) ]]; then
        build-devenv $@
    fi
}

function start-devenv {
    build-devenv-if-not-exists $@;
    docker-compose -p uxboxdev -f docker/devenv/docker-compose.yaml up -d;
}

function stop-devenv {
    docker-compose -p uxboxdev -f docker/devenv/docker-compose.yaml stop -t 2;
}

function drop-devenv {
    docker-compose -p uxboxdev -f docker/devenv/docker-compose.yaml down -t 2 -v;

    echo "Clean old development image $DEVENV_IMGNAME..."
    docker images $DEVENV_IMGNAME -q | awk '{print $3}' | xargs --no-run-if-empty docker rmi
}

function run-devenv {
    if [[ ! $(docker ps -f "name=uxbox-devenv-main" -q) ]]; then
        start-devenv
    fi

    docker exec -ti uxbox-devenv-main /home/uxbox/start-tmux.sh
}

function build-frontend {
    build-devenv-if-not-exists;

    local IMAGE=$DEVENV_IMGNAME:latest;

    echo "Running development image $IMAGE to build frontend."
    docker run -t --rm \
           --mount source=`pwd`,type=bind,target=/home/uxbox/uxbox \
           --mount source=${HOME}/.m2,type=bind,target=/home/uxbox/.m2 \
           -w /home/uxbox/uxbox/frontend \
           $IMAGE ./scripts/build-app.sh
}

function build-exporter {
    build-devenv-if-not-exists;

    local IMAGE=$DEVENV_IMGNAME:latest;

    echo "Running development image $IMAGE to build frontend."
    docker run -t --rm \
           --mount source=`pwd`,type=bind,target=/home/uxbox/uxbox \
           --mount source=${HOME}/.m2,type=bind,target=/home/uxbox/.m2 \
           -w /home/uxbox/uxbox/exporter \
           $IMAGE ./scripts/build.sh
}

function build-backend {
    rm -rf ./backend/target/dist
    mkdir -p ./backend/target/dist

    rsync -ar \
          --exclude="/tests*" \
          --exclude="/resources/public/media" \
          --exclude="/file-uploads" \
          --exclude="/target" \
          --exclude="/scripts" \
          --exclude="/.*" \
          ./backend/ ./backend/target/dist/

    rsync -ar \
          ./common/ ./backend/target/dist/common/
}

function log-devenv {
    docker-compose -p uxboxdev -f docker/devenv/docker-compose.yaml logs -f --tail=50
}

function usage {
    echo "UXBOX build & release manager v$REV"
    echo "USAGE: $0 OPTION"
    echo "Options:"
    # echo "- clean                            Stop and clean up docker containers"
    # echo ""
    echo "- build-devenv                     Build docker development oriented image; (can specify external user id in parameter)"
    echo "- start-devenv                     Start the development oriented docker-compose service."
    echo "- stop-devenv                      Stops the development oriented docker-compose service."
    echo "- drop-devenv                      Remove the development oriented docker-compose containers, volumes and clean images."
    echo "- run-devenv                       Attaches to the running devenv container and starts development environment"
    echo "                                   based on tmux (frontend at localhost:3449, backend at localhost:6060)."
    echo ""
    echo "- run-all-tests                    Execute unit tests for both backend and frontend."
    echo "- run-frontend-tests               Execute unit tests for frontend only."
    echo "- run-backend-tests                Execute unit tests for backend only."
}

case $1 in
    ## devenv related commands
    build-devenv)
        build-devenv ${@:2}
        ;;
    start-devenv)
        start-devenv ${@:2}
        ;;
    run-devenv)
        run-devenv ${@:2}
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

    ## testin related commands

    # run-all-tests)
    #     run-all-tests ${@:2}
    #     ;;
    # run-frontend-tests)
    #     run-frontend-tests ${@:2}
    #     ;;
    # run-backend-tests)
    #     run-backend-tests ${@:2}
    #     ;;

    # production builds
    build-frontend)
        build-frontend
        ;;

    build-backend)
        build-backend
        ;;

    build-exporter)
        build-exporter
        ;;

    *)
        usage
        ;;
esac
