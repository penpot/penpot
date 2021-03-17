#!/usr/bin/env bash
set -e

export ORGANIZATION="penpotapp";
export DEVENV_IMGNAME="$ORGANIZATION/devenv";
export DEVENV_PNAME="penpotdev";

export CURRENT_USER_ID=$(id -u);
export CURRENT_VERSION=$(git describe --tags);
export CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD);
export CURRENT_HASH=$(git rev-parse --short HEAD);
export CURRENT_BUILD=$(date '+%Y%m%d%H%M');

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
    docker-compose -p $DEVENV_PNAME -f docker/devenv/docker-compose.yaml up -d;
}

function stop-devenv {
    docker-compose -p $DEVENV_PNAME -f docker/devenv/docker-compose.yaml stop -t 2;
}

function drop-devenv {
    docker-compose -p $DEVENV_PNAME -f docker/devenv/docker-compose.yaml down -t 2 -v;

    echo "Clean old development image $DEVENV_IMGNAME..."
    docker images $DEVENV_IMGNAME -q | awk '{print $3}' | xargs --no-run-if-empty docker rmi
}

function log-devenv {
    docker-compose -p $DEVENV_PNAME -f docker/devenv/docker-compose.yaml logs -f --tail=50
}

function run-devenv {
    if [[ ! $(docker ps -f "name=penpot-devenv-main" -q) ]]; then
        start-devenv
    fi

    docker exec -ti penpot-devenv-main sudo -EH -u penpot /home/start-tmux.sh
}

function build {
    pull-devenv-if-not-exists;
    docker volume create ${DEVENV_PNAME}_user_data;
    docker run -t --rm \
           --mount source=${DEVENV_PNAME}_user_data,type=volume,target=/home/penpot/ \
           --mount source=`pwd`,type=bind,target=/home/penpot/penpot \
           -e EXTERNAL_UID=$CURRENT_USER_ID \
           -e SHADOWCLJS_EXTRA_PARAMS=$SHADOWCLJS_EXTRA_PARAMS \
           -w /home/penpot/penpot/$1 \
           $DEVENV_IMGNAME:latest sudo -EH -u penpot ./scripts/build.sh
}

function build-frontend {
    build "frontend";
}

function build-exporter {
    build "exporter";
}

function build-backend {
    build "backend";
}

function build-app-bundle {
    local version="$CURRENT_VERSION";
    local bundle_dir="./bundle-app";

    build "frontend";
    build "backend";

    rm -rf $bundle_dir
    mkdir -p $bundle_dir;
    cp -r ./frontend/target/dist $bundle_dir/frontend;
    cp -r ./backend/target/dist $bundle_dir/backend;

    if [ $CURRENT_BRANCH != "main" ]; then
        version="$CURRENT_BRANCH-$CURRENT_VERSION";
    fi;

    echo $version > $bundle_dir/version.txt

    sed -i -re "s/\%version\%/$version/g" $bundle_dir/frontend/index.html;
    sed -i -re "s/\%version\%/$version/g" $bundle_dir/backend/main/app/config.clj;
}

function build-exporter-bundle {
    local version="$CURRENT_VERSION";
    local bundle_dir="./bundle-exporter";

    build "exporter";

    rm -rf $bundle_dir;
    cp -r ./exporter/target $bundle_dir;

    if [ $CURRENT_BRANCH != "main" ]; then
        version="$CURRENT_BRANCH-$CURRENT_VERSION";
    fi;

    echo $version > $bundle_dir/version.txt
}

function usage {
    echo "PENPOT build & release manager"
    echo "USAGE: $0 OPTION"
    echo "Options:"
    # echo "- clean                            Stop and clean up docker containers"
    # echo ""
    echo "- pull-devenv                      Pulls docker development oriented image"
    echo "- build-devenv                     Build docker development oriented image"
    echo "- start-devenv                     Start the development oriented docker-compose service."
    echo "- stop-devenv                      Stops the development oriented docker-compose service."
    echo "- drop-devenv                      Remove the development oriented docker-compose containers, volumes and clean images."
    echo "- run-devenv                       Attaches to the running devenv container and starts development environment"
    echo "                                   based on tmux (frontend at localhost:3449, backend at localhost:6060)."
    echo ""
    # echo "- run-all-tests                    Execute unit tests for both backend and frontend."
    # echo "- run-frontend-tests               Execute unit tests for frontend only."
    # echo "- run-backend-tests                Execute unit tests for backend only."
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
        build-frontend;
        ;;

    build-backend)
        build-backend;
        ;;

    build-exporter)
        build-exporter;
        ;;

    build-app-bundle)
        build-app-bundle;
        ;;

    build-exporter-bundle)
        build-exporter-bundle;
        ;;

    # Docker Image Tasks
    build-images)
        build-images;
        ;;

    # publish-snapshot-images)
    #     publish-snapshot-images;
    #     ;;

    # publish-latest-images)
    #     publish-latest-images;
    #     ;;

    *)
        usage
        ;;
esac
