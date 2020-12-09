#!/usr/bin/env bash
set -e

export ORGANIZATION="penpotapp";
export DEVENV_IMGNAME="$ORGANIZATION/devenv";
export DEVENV_PNAME="penpotdev";

export CURRENT_USER_ID=$(id -u);
export CURRENT_VERSION=$(git describe --tags);
export CURRENT_GIT_BRANCH=$(git rev-parse --abbrev-ref HEAD);

function build-devenv {
    echo "Building development image $DEVENV_IMGNAME:latest..."

    pushd docker/devenv;
    docker build -t $DEVENV_IMGNAME:latest .
    popd;
}

function publish-devenv {
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

function build-bundle {
    build "frontend";
    build "exporter";
    build "backend";

    rm -rf ./bundle
    mkdir -p ./bundle
    mv ./frontend/target/dist ./bundle/frontend
    mv ./backend/target/dist ./bundle/backend
    mv ./exporter/target ./bundle/exporter

    local name="penpot-$CURRENT_VERSION";
    echo $CURRENT_VERSION > ./bundle/version.txt

    sed -i -re "s/\%version\%/$CURRENT_VERSION/g" ./bundle/frontend/index.html;
    sed -i -re "s/\%version\%/$CURRENT_VERSION/g" ./bundle/backend/main/app/config.clj;

    local generate_tar=${PENPOT_BUILD_GENERATE_TAR:-"true"};

    if [ $generate_tar == "true" ]; then
        pushd bundle/
        tar -cvf ../$name.tar *;
        popd

        xz -vez1f -T4 $name.tar

        echo "##############################################################";
        echo "# Generated $name.tar.xz";
        echo "##############################################################";
    fi
}

function build-image {
    set -ex;

    local image=$1;

    pushd ./docker/images;
    local docker_image="$ORGANIZATION/$image";
    docker build -t $docker_image:$CURRENT_VERSION -f Dockerfile.$image .;
    popd;
}

function build-images {
    local bundle_file="penpot-$CURRENT_VERSION.tar.xz";

    if [ ! -f $bundle_file ]; then
        echo "File '$bundle_file' does not exists.";
        exit 1;
    fi

    rm -rf ./docker/images/bundle;
    mkdir -p ./docker/images/bundle;

    local bundle_file_path=`readlink -f $bundle_file`;
    echo "Building docker image from: $bundle_file_path.";

    pushd ./docker/images/bundle;
    tar xvf $bundle_file_path;
    popd

    build-image "backend";
    build-image "frontend";
    build-image "exporter";
}

function publish-snapshot {
    set -x
    docker tag $ORGANIZATION/frontend:$CURRENT_VERSION $ORGANIZATION/frontend:$CURRENT_GIT_BRANCH
    docker tag $ORGANIZATION/backend:$CURRENT_VERSION $ORGANIZATION/backend:$CURRENT_GIT_BRANCH
    docker tag $ORGANIZATION/exporter:$CURRENT_VERSION $ORGANIZATION/exporter:$CURRENT_GIT_BRANCH

    docker push $ORGANIZATION/frontend:$CURRENT_GIT_BRANCH;
    docker push $ORGANIZATION/backend:$CURRENT_GIT_BRANCH;
    docker push $ORGANIZATION/exporter:$CURRENT_GIT_BRANCH;
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


    publish-devenv)
        publish-devenv ${@:2}
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

    build-bundle)
        build-bundle
        ;;

    # Docker Image Tasks
    build-images)
        build-images;
        ;;

    publish-snapshot)
        publish-snapshot ${@:2}
        ;;

    *)
        usage
        ;;
esac
