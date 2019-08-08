#!/usr/bin/env bash
set -e

REV=`git log -n 1 --pretty=format:%h -- docker/`
IMGNAME="uxbox-devenv"

function kill-devenv-container {
    echo "Cleaning development container $IMGNAME:$REV..."
    docker ps -a -f name=$IMGNAME -q | xargs --no-run-if-empty docker kill
}

function remove-devenv-images {
    echo "Clean old development image $IMGNAME..."
    docker images $IMGNAME -q | awk '{print $3}' | xargs --no-run-if-empty docker rmi
}

function build-devenv-image {
    echo "Building development image $IMGNAME:$REV..."
    docker build --rm=true \
           -t $IMGNAME:$REV \
           -t $IMGNAME:latest \
           --build-arg EXTERNAL_UID=$(id -u) \
           --label="io.uxbox.devenv" \
           docker/devenv
}

function build-devenv-image-if-not-exists {
    if [[ ! $(docker images $IMGNAME:$REV -q) ]]; then
        build-devenv-image
    fi
}

function run-devenv {
    kill-devenv-container;
    build-devenv-image-if-not-exists;

    mkdir -p $HOME/.m2
    rm -rf ./frontend/node_modules
    mkdir -p \
        ./frontend/resources/public/css \
        ./frontend/resources/public/view/css

    CONTAINER=$IMGNAME:latest

    echo "Running development image $CONTAINER..."
    docker run --rm -ti \
         -v `pwd`:/home/uxbox/uxbox  \
         -v $HOME/.m2:/home/uxbox/.m2 \
         -v $HOME/.gitconfig:/home/uxbox/.gitconfig \
         -p 3449:3449 -p 6060:6060 -p 9090:9090 \
         --name "uxbox-devenv" \
         $CONTAINER
}

function run-all-tests {
    echo "Testing frontend..."
    run-frontend-tests || exit 1;
    echo "Testing backend..."
    run-backend-tests || exit 1;
}

function run-frontend-tests {
    build-devenv-image-if-not-exists;

    CONTAINER=$IMGNAME:latest

    echo "Running development image $CONTAINER to test backend..."
    docker run -ti --rm \
           -w /home/uxbox/uxbox/frontend \
           -v `pwd`:/home/uxbox/uxbox \
           -v $HOME/.m2:/home/uxbox/.m2 \
           $CONTAINER ./scripts/build-and-run-tests.sh
}

function run-backend-tests {
    build-devenv-image-if-not-exists;

    CONTAINER=$IMGNAME:latest

    docker run -ti --rm \
           -w /home/uxbox/uxbox/backend \
           -v `pwd`:/home/uxbox/uxbox \
           -v $HOME/.m2:/home/uxbox/.m2 \
           $CONTAINER ./scripts/run-tests-in-docker.sh
}

function build-frontend-local {
    build-devenv-image-if-not-exists;

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
           $CONTAINER ./scripts/build-$BUILD_TYPE.sh
}

function build-frontend-production-image {
    build-frontend-local "production" || exit 1;
    rm -rf docker/frontend/dist || exit 1;
    cp -vr frontend/dist docker/frontend/ || exit 1;

    docker build --rm=true \
           -t uxbox-frontend-production:$REV \
           -t uxbox-frontend-production:latest \
           docker/frontend/;

    rm -rf docker/frontend/dist || exit 1;
}

function build-frontend-develop-image {
    build-frontend-local "develop" || exit 1;
    rm -rf docker/frontend/dist || exit 1;
    cp -vr frontend/dist docker/frontend/ || exit 1;

    docker build --rm=true \
           -t uxbox-frontend-develop:$REV \
           -t uxbox-frontend-develop:latest \
           docker/frontend/;

    rm -rf docker/frontend/dist || exit 1;
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

function build-backend-production-image {
    build-backend-local || exit 1;
    rm -rf docker/backend/dist || exit 1;
    cp -vr backend/dist docker/backend/ || exit 1;

    docker build --rm=true \
           -t uxbox-backend-production:$REV \
           -t uxbox-backend-production:latest \
           docker/backend/;

    rm -rf docker/backend/dist || exit 1;
}

function build-images {
    echo "Building frontend production image ..."
    build-frontend-production-image || exit 1;
    echo "Building frontend develop image ..."
    build-frontend-develop-image || exit 1;
    echo "Building backend production image ..."
    build-backend-production-image || exit 1;
}

function run {
    if [[ ! $(docker images uxbox-backend-production:latest) ]]; then
        build-production-backend-image
    fi

    if [[ ! $(docker images uxbox-frontend-production:latest) ]]; then
        build-production-frontend-image
    fi

    if [[ ! $(docker images uxbox-frontend-develop:latest) ]]; then
        build-develop-frontend-image
    fi

    echo "Running production images..."
    docker-compose -p uxbox -f ./docker/docker-compose.yml up -d
}

function log {
    docker-compose -p uxbox -f docker/docker-compose.yml logs -f --tail=50
}

function stop {
    echo "Stoping containers..."
    docker-compose -p uxbox -f ./docker/docker-compose.yml stop
}

function usage {
    echo "UXBOX build & release manager v$REV"
    echo "USAGE: $0 OPTION"
    echo "Options:"
    echo "- clean                            Stop and clean up docker containers"
    echo "- build-devenv-image               Build docker container for development with tmux"
    echo "- run-devenv                       Run (and build if necessary) development container (frontend at localhost:3449, backend at localhost:6060)"
    echo "- run-all-tests                    Execute unit tests for both backend and frontend"
    echo "- run-frontend-tests               Execute unit tests for frontend only"
    echo "- run-backend-tests                Execute unit tests for backend only"
    echo "- build-images                     Build a 'release ready' docker images for both backend and frontend"
    echo "- build-frontend-develop-image     Build a 'release ready' docker image for frontend (develop build)"
    echo "- build-frontend-production-image  Build a 'release ready' docker images for frontend"
    echo "- build-backend-production-image   Build a 'release ready' docker images for backend"
    echo "- run                              Run 'production ready' docker compose"
    echo "- stop                             Stop 'production ready' docker compose"
}

case $1 in
    clean)
        kill-devenv-container
        remove-devenv-images
        ;;
    build-devenv-image)
        build-devenv-image
        ;;
    run-devenv)
        run-devenv
        ;;
    run-all-tests)
        run-all-tests
        ;;
    run-frontend-tests)
        run-frontend-tests
        ;;
    run-backend-tests)
        run-backend-tests
        ;;

    build-images)
        build-images
        ;;
    build-frontend-develop-image)
        build-frontend-develop-image;
        ;;
    build-frontend-production-image)
        build-frontend-production-image;
        ;;
    build-backend-production-image)
        build-backend-production-image;
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

    *)
        usage
        ;;
esac
