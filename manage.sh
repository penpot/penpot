#!/usr/bin/env bash
set -e

REV=`git log -n 1 --pretty=format:%h -- docker/`
IMGNAME="uxboxdev_main"

function remove-devenv-images {
    echo "Clean old development image $IMGNAME..."
    docker images $IMGNAME -q | awk '{print $3}' | xargs --no-run-if-empty docker rmi
}

function build-devenv {
    echo "Building development image $IMGNAME:latest with UID $EXTERNAL_UID..."

    local EXTERNAL_UID=${1:-$(id -u)}
    docker-compose -p uxboxdev -f docker/devenv/docker-compose.yaml \
        build --build-arg EXTERNAL_UID=$EXTERNAL_UID --force-rm;
}

function build-devenv-if-not-exists {
    if [[ ! $(docker images $IMGNAME:latest -q) ]]; then
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
    remove-devenv-images;
}

function run-devenv {
    if [[ ! $(docker ps -f "name=uxboxdev-main" -q) ]]; then
        start-devenv
    fi

    docker exec -ti uxboxdev-main /home/uxbox/start-tmux.sh
}

function run-all-tests {
    echo "Testing frontend..."
    run-frontend-tests $@ || exit 1;
    echo "Testing backend..."
    run-backend-tests $@ || exit 1;
}

function run-frontend-tests {
    build-devenv-if-not-exists $@;

    CONTAINER=$IMGNAME:latest

    echo "Running development image $CONTAINER to test backend..."
    docker run -ti --rm \
           -w /home/uxbox/uxbox/frontend \
           -v `pwd`:/home/uxbox/uxbox \
           -v $HOME/.m2:/home/uxbox/.m2 \
           $CONTAINER ./scripts/build-and-run-tests.sh
}

function run-backend-tests {
    build-devenv-if-not-exists $@;

    CONTAINER=$IMGNAME:latest

    docker run -ti --rm \
           -w /home/uxbox/uxbox/backend \
           -v `pwd`:/home/uxbox/uxbox \
           -v $HOME/.m2:/home/uxbox/.m2 \
           $CONTAINER ./scripts/run-tests-in-docker.sh
}

function build-frontend-local {
    build-devenv-if-not-exists;

    mkdir -p $HOME/.m2
    rm -rf ./frontend/node_modules

    CONTAINER=$IMGNAME:latest;
    BUILD_TYPE=$1;

    echo "Running development image $CONTAINER to build frontend $BUILD_TYPE ..."
    docker run -ti --rm \
           -w /home/uxbox/uxbox/frontend \
           -v `pwd`:/home/uxbox/uxbox  \
           -v $HOME/.m2:/home/uxbox/.m2 \
           -e UXBOX_API_URL="/api" \
           -e UXBOX_VIEW_URL="/view" \
           -e UXBOX_DEMO_WARNING=true \
           $CONTAINER ./scripts/build-$BUILD_TYPE.sh
}

function build-frontend-image {
    echo "#############################################"
    echo "## START build 'uxbox-frontend' image.     ##"
    echo "#############################################"
    build-frontend-local "dist" || exit 1;
    rm -rf docker/frontend/dist || exit 1;
    cp -vr frontend/dist docker/frontend/ || exit 1;

    docker build --rm=true \
           -t uxbox-frontend:$REV \
           -t uxbox-frontend:latest \
           docker/frontend/;

    rm -rf docker/frontend/dist || exit 1;
    echo "#############################################"
    echo "## END build 'uxbox-frontend' image.       ##"
    echo "#############################################"
}

function build-frontend-dbg-image {
    echo "#############################################"
    echo "## START build 'uxbox-frontend-dbg' image. ##"
    echo "#############################################"

    build-frontend-local "dbg-dist" || exit 1;
    rm -rf docker/frontend/dist || exit 1;
    cp -vr frontend/dist docker/frontend/ || exit 1;

    docker build --rm=true \
           -t uxbox-frontend-dbg:$REV \
           -t uxbox-frontend-dbg:latest \
           docker/frontend/;

    rm -rf docker/frontend/dist || exit 1;

    echo "#############################################"
    echo "## END build 'uxbox-frontend-dbg' image.   ##"
    echo "#############################################"
}

function build-backend-local {
    echo "Prepare backend dist..."

    rm -rf ./backend/dist

    rsync -ar \
      --exclude="/test" \
      --exclude="/resources/public/media" \
      --exclude="/target" \
      --exclude="/scripts" \
      --exclude="/.*" \
      ./backend/ ./backend/dist/
}

function build-backend-image {
    echo "#############################################"
    echo "## START build 'uxbox-backend' image.      ##"
    echo "#############################################"

    build-backend-local || exit 1;
    rm -rf docker/backend/dist || exit 1;
    cp -vr backend/dist docker/backend/ || exit 1;

    docker build --rm=true \
           -t uxbox-backend:$REV \
           -t uxbox-backend:latest \
           docker/backend/;

    rm -rf docker/backend/dist || exit 1;
    echo "#############################################"
    echo "## END build 'uxbox-backend' image.        ##"
    echo "#############################################"
}

function build-images {
    build-devenv-if-not-exists $@;

    echo "Building frontend image ..."
    build-frontend-image || exit 1;
    echo "Building frontend dbg image ..."
    build-frontend-dbg-image || exit 1;
    echo "Building backend image ..."
    build-backend-image || exit 1;
}

function run {
    if [[ ! $(docker images uxbox-backend:latest) ]]; then
        build-backend-image
    fi

    if [[ ! $(docker images uxbox-frontend:latest) ]]; then
        build-frontend-image
    fi

    if [[ ! $(docker images uxbox-frontend-dbg:latest) ]]; then
        build-frontend-dbg-image
    fi

    echo "Running images..."
    docker-compose -p uxbox -f ./docker/docker-compose.yml up -d
}

function log {
    docker-compose -p uxbox -f docker/docker-compose.yml logs -f --tail=50
}

function log-devenv {
    docker-compose -p uxboxdev -f docker/devenv/docker-compose.yaml logs -f --tail=50
}

function stop {
    echo "Stoping containers..."
    docker-compose -p uxbox -f ./docker/docker-compose.yml stop
}

function drop {
    docker-compose -p uxbox -f docker/docker-compose.yml down -t 2 -v;
}

function usage {
    echo "UXBOX build & release manager v$REV"
    echo "USAGE: $0 OPTION"
    echo "Options:"
    echo "- clean                            Stop and clean up docker containers"
    echo ""
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
    clean)
        remove-devenv-images
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

    run-all-tests)
        run-all-tests ${@:2}
        ;;
    run-frontend-tests)
        run-frontend-tests ${@:2}
        ;;
    run-backend-tests)
        run-backend-tests ${@:2}
        ;;

    # production related comands

    build-images)
        build-images
        ;;
    build-frontend-dbg-image)
        build-frontend-dbg-image
        ;;
    build-frontend-image)
        build-frontend-image
        ;;
    build-backend-image)
        build-backend-image
        ;;

    run)
        run
        ;;

    log)
        log
        ;;

    stop)
        stop
        ;;

    drop)
        drop
        ;;

    *)
        usage
        ;;
esac
