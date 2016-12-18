#!/usr/bin/env bash

REV=`git rev-parse --short HEAD`
IMGNAME="uxbox"

function kill_container {
    if $(sudo docker ps |grep -q $IMGNAME); then
        sudo docker ps |grep $IMGNAME | awk '{print $1}' | xargs --no-run-if-empty sudo docker kill
    fi
}

function start_local {
    tmux -2 new-session -d -s uxbox

    tmux new-window -t uxbox:1 -n 'figwheel'
    tmux select-window -t uxbox:1
    tmux send-keys -t uxbox 'cd frontend' enter
    tmux send-keys -t uxbox 'npm run figwheel' enter

    tmux new-window -t uxbox:2 -n 'backend'
    tmux select-window -t uxbox:2
    tmux send-keys -t uxbox 'cd backend' enter
    # tmux send-keys -t uxbox 'bash ./scripts/fixtures.sh' enter
    # tmux send-keys -t uxbox 'bash ./scripts/run.sh' enter

    tmux rename-window -t uxbox:0 'gulp'
    tmux select-window -t uxbox:0
    tmux send-keys -t uxbox 'cd frontend' enter
    tmux send-keys -t uxbox 'if [ ! -e ./node_modules ]; then npm install; fi' enter
    tmux send-keys -t uxbox 'npm run watch' enter

    tmux -2 attach-session -t uxbox
}

function build_image {
    kill_container
    sudo docker build --rm=true -t $IMGNAME:$REV docker/
}

function run_image {
    kill_container

    if ! $(sudo docker images|grep $IMGNAME |grep -q $REV); then
        build_image
    fi

    sudo docker run -ti \
         -v `pwd`:/home/uxbox/uxbox  \
         -v $HOME/.m2:/home/uxbox/.m2 \
         -v $HOME/.gitconfig:/home/uxbox/.gitconfig \
         -p 3449:3449 -p 6060:6060 -p 9090:9090 $IMGNAME:$REV
}

function usage {
    echo "USAGE: $0 [ build | run | start ]"
}

case $1 in
    build)
        build_image
        ;;
    run)
        run_image
        ;;

    start)
        start_local
        ;;
    *)
        usage
        ;;
esac
