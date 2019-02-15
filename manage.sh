#!/usr/bin/env bash

REV=`git rev-parse --short HEAD`
IMGNAME="uxbox"

function kill_container {
    echo "Cleaning development image..."
    if $(sudo docker ps | grep -q $IMGNAME); then
        sudo docker ps | grep $IMGNAME | awk '{print $1}' | xargs --no-run-if-empty sudo docker kill
    fi
}

function build_image {
    kill_container
    echo "Building development image..."
    sudo docker build --rm=true -t $IMGNAME:$REV docker/
}

function run_image {
    kill_container

    if ! $(sudo docker images | grep $IMGNAME | grep -q $REV); then
        build_image
    fi

    mkdir -p $HOME/.m2
    rm -rf ./frontend/node_modules

    echo "Running development image..."
    sudo docker run -ti \
         -v `pwd`:/home/uxbox/uxbox  \
         -v $HOME/.m2:/home/uxbox/.m2 \
         -v $HOME/.gitconfig:/home/uxbox/.gitconfig \
         -p 3449:3449 -p 6060:6060 -p 9090:9090 $IMGNAME:$REV
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
    sudo docker build --rm=true -t $IMGNAME-frontend:$REV frontend/
    echo "Frontend release image generated"

    echo "Building backend release..."
    rm -rf ./backend/dist
    sudo docker build --rm=true -t $IMGNAME-backend:$REV backend/
    echo "Backend release image generated"
}

function usage {
    echo "USAGE: $0 [ build | run | release-local | release-docker ]"
}

case $1 in
    build)
        build_image
        ;;
    run)
        run_image
        ;;
    release-local)
        release_local
        ;;
    release-docker)
        release_image
        ;;
    *)
        usage
        ;;
esac
