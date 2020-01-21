#!/usr/bin/env bash
set -e

REV=`git log -n 1 --pretty=format:%h -- docker/`
DEVENV_IMGNAME="uxbox-devenv"
BUILDENV_IMGNAME="uxbox-buildenv"

function build-devenv {
    echo "Building development image $DEVENV_IMGNAME:latest with UID $EXTERNAL_UID..."

    cp ./frontend/build/package.json docker/devenv/files/package.json;

    local EXTERNAL_UID=${1:-$(id -u)}

    docker build --rm=true --force-rm \
           -t $DEVENV_IMGNAME:latest \
           --build-arg EXTERNAL_UID=$EXTERNAL_UID \
           docker/devenv/;

    rm -rf docker/devenv/files/package.json;
}

function build-buildenv {
    echo "Building buildenv image..."

    docker volume create ${BUILDENV_IMGNAME}-m2

    cp ./frontend/build/package.json docker/buildenv/files/package.json;

    docker build --rm=true \
           -t $BUILDENV_IMGNAME:latest \
           docker/buildenv/;

    rm -rf docker/buildenv/files/package.json;
}

function build-devenv-if-not-exists {
    if [[ ! $(docker images $DEVENV_IMGNAME:latest -q) ]]; then
        build-devenv $@
    fi
}

function build-buildenv-if-not-exists {
    if [[ ! $(docker images $BUILDENV_IMGNAME:latest -q) ]]; then
        build-buildenv $@
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

# function run-all-tests {
#     echo "Testing frontend..."
#     run-frontend-tests $@ || exit 1;
#     echo "Testing backend..."
#     run-backend-tests $@ || exit 1;
# }

# function run-frontend-tests {
#     build-devenv-if-not-exists $@;

#     IMAGE=$DEVENV_IMGNAME:latest

#     echo "Running development image $CONTAINER to test backend..."
#     docker run -ti --rm \
#            -w /home/uxbox/uxbox/frontend \
#            -v `pwd`:/home/uxbox/uxbox \
#            -v $HOME/.m2:/home/uxbox/.m2 \
#            $IMAGE ./scripts/build-and-run-tests.sh
# }

# function run-backend-tests {
#     build-devenv-if-not-exists $@;

#     IMAGE=$DEVENV_IMGNAME:latest

#     docker run -ti --rm \
#            -w /home/uxbox/uxbox/backend \
#            -v `pwd`:/home/uxbox/uxbox \
#            -v $HOME/.m2:/home/uxbox/.m2 \
#            $IMAGE ./scripts/run-tests-in-docker.sh
# }

function build-frontend {
    build-buildenv-if-not-exists;

    local IMAGE=$BUILDENV_IMGNAME:latest;

    echo "Running development image $IMAGE to build frontend."
    docker run -t --rm \
           --mount source=`pwd`,type=bind,target=/root/uxbox  \
           --mount source=${BUILDENV_IMGNAME}-m2,target=/root/.m2 \
           -w /root/uxbox/frontend \
           -e UXBOX_API_URL=${UXBOX_API_URL} \
           -e UXBOX_DEMO_WARNING=${UXBOX_DEMO_WARNING} \
           $IMAGE ./scripts/build-app.sh
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
    echo ""
    echo "- build-images                     Build a 'release ready' docker images for both backend and frontend"
    echo "- build-frontend-image             Build a 'release ready' docker image for frontend (debug version)"
    echo "- build-frontend-dbg-image         Build a debug docker image for frontend"
    echo "- build-backend-image              Build a 'release ready' docker images for backend"
    echo "- log                              Attach to docker logs."
    echo "- run                              Run 'production ready' docker compose"
    echo "- stop                             Stop 'production ready' docker compose"
    echo "- drop                             Remove the production oriented docker-compose containers and volumes."
}

case $1 in
    build-buildenv)
        build-buildenv ${@:2}
        ;;

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

    *)
        usage
        ;;
esac
