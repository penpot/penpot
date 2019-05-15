#!/usr/bin/env bash
set -e

REV=`git rev-parse --short HEAD`
IMGNAME="uxbox"

function kill_container {
    echo "Cleaning development container $IMGNAME:$REV..."
    if $(sudo docker ps | grep -q $IMGNAME); then
        sudo docker ps | grep $IMGNAME | awk '{print $1}' | xargs --no-run-if-empty sudo docker kill
    fi
    if $(sudo docker ps -a | grep -q $IMGNAME); then
        sudo docker ps -a | grep $IMGNAME | awk '{print $1}' | xargs --no-run-if-empty sudo docker rm
    fi
}

function remove_image {
    echo "Clean old development image $IMGNAME..."
    sudo docker images | grep $IMGNAME | awk '{print $3}' | xargs --no-run-if-empty sudo docker rmi
}

function build_image {
    kill_container
    remove_image
    echo "Building development image $IMGNAME:$REV..."
    sudo docker build --rm=true -t $IMGNAME:$REV docker/
}

function run_image {
    kill_container

    if ! $(sudo docker images | grep $IMGNAME | grep -q $REV); then
        build_image
    fi

    mkdir -p $HOME/.m2
    rm -rf ./frontend/node_modules

    CONTAINER=$IMGNAME:$REV
    #CONTAINER=monogramm/uxbox:develop

    echo "Running development image $CONTAINER..."
    sudo docker run -ti \
         -v `pwd`:/home/uxbox/uxbox  \
         -v $HOME/.m2:/home/uxbox/.m2 \
         -v $HOME/.gitconfig:/home/uxbox/.gitconfig \
         -p 3449:3449 -p 6060:6060 -p 9090:9090 \
         $CONTAINER
}

function test {
    kill_container

    echo "TODO Testing backend (require running postgresql)..."
    cd ./backend
    #lein test
    cd ..

    echo "Testing frontend..."
    cd ./frontend
    ./scripts/build-tests
    node ./out/tests.js
    cd ..
}

function release_local {
    cd frontend
    echo "Building frontend release..."
    rm -rf ./dist
    rm -rf ./node_modules
    npm install
    npm run dist
    ./scripts/dist-main
    ./scripts/dist-view
    ./scripts/dist-worker
    echo "Frontend release generated in $(pwd)/dist"

    cd ../backend
    echo "Building backend release..."
    rm -rf ./dist
    ./scripts/dist.sh
    echo "Backend release generated in $(pwd)/dist"

    cd ..
}

function release_image {
    echo "Building frontend release..."
    rm -rf ./frontend/dist ./frontend/node_modules ./frontend/dist
    sudo docker build --rm=true -t ${IMGNAME}_frontend:$REV frontend/
    echo "Frontend release image generated"

    echo "Building backend release..."
    rm -rf ./backend/dist
    sudo docker build --rm=true -t ${IMGNAME}_backend:$REV backend/
    echo "Backend release image generated"
}

function run_release {
    kill_container

    echo "Running production images..."
    sudo docker-compose up -d
}

function usage {
    echo "UXBOX build & release manager v$REV"
    echo "USAGE: $0 [ clean | build | run | test | release-local | release-docker | run-release ]"
    echo "Options:"
    echo "- clean           Stop and clean up docker containers"
    echo "- build           Build docker container for development with tmux"
    echo "- run             Run (and build if necessary) development container (frontend at localhost:3449, backend at localhost:6060)"
    echo "- test            Execute frontend unit tests (backend unit tests no available yet)"
    echo "- release-local   Build a 'production ready' release"
    echo "- release-docker  Build a 'production ready' docker container"
    echo "- run-release     Run a 'production ready' docker-compose environment (frontend at localhost:80, backend at localhost:6060)"
}

case $1 in
    clean)
        kill_container
        remove_image
        ;;
    build)
        build_image
        ;;
    run)
        run_image
        ;;
    test)
        test
        ;;
    release-local)
        release_local
        ;;
    release-docker)
        release_image
        ;;
    run-release)
        run_release
        ;;
    *)
        usage
        ;;
esac
