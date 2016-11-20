#!/usr/bin/env bash

REV=`git rev-parse --short HEAD`
IMGNAME="uxbox"

function kill_container {
    if $(sudo docker ps |grep -q $IMGNAME); then
        sudo docker ps |grep $IMGNAME | awk '{print $1}' | xargs --no-run-if-empty sudo docker kill
    fi
}

function build_image {
    kill_container
    sudo docker build --rm=true -t $IMGNAME:$REV docker/
}

function initialize {
    if [ ! -e ./uxbox ]; then
        git clone git@github.com:uxbox/uxbox.git
    fi

    if [ ! -e ./uxbox-backend ]; then
        git clone git@github.com:uxbox/uxbox-backend.git
    fi
}

function run_image {
    kill_container
    initialize

    if ! $(sudo docker images|grep $IMGNAME |grep -q $REV); then
        build_image
    fi

    sudo docker run -ti \
         -v `pwd`/uxbox:/home/uxbox/uxbox  \
         -v `pwd`/uxbox-backend:/home/uxbox/uxbox-backend \
         -v $HOME/.m2:/home/uxbox/.m2 \
         -v $HOME/.gitconfig:/home/uxbox/.gitconfig \
         -p 3449:3449 -p 6060:6060 -p 9090:9090 $IMGNAME:$REV
}

function usage {
    echo "USAGE: $0 [ build | run | init ]"
}

case $1 in
    build)
        build_image
        ;;
    run)
        run_image
        ;;

    init)
        initialize
        ;;
    *)
        usage
        ;;
esac
