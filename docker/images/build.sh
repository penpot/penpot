#!/usr/bin/env bash
set -x

DOCKER_CLI_EXPERIMENTAL=enabled
ORG=${PENPOT_DOCKER_NAMESPACE:-penpotapp};
PLATFORM=${PENPOT_BUILD_PLATFORM:-linux/amd64};
IMAGE=${1:-backend};

DOCKER_IMAGE="$ORG/$IMAGE";
OPTIONS="-t $DOCKER_IMAGE:$PENPOT_BUILD_VERSION";

IFS=", "
read -a TAGS <<< $PENPOT_BUILD_TAGS;

for element in "${TAGS[@]}"; do
    OPTIONS="$OPTIONS -t $DOCKER_IMAGE:$element";
done

if [ "$PENPOT_BUILD_PUSH" = "true" ]; then
    OPTIONS="--push $OPTIONS"
else
    OPTIONS="--load $OPTIONS"
fi

docker buildx inspect penpot > /dev/null 2>&1;
docker run --privileged --rm tonistiigi/binfmt --install all

if [ $? -eq 1 ]; then
    docker buildx create --name=penpot --use
    docker buildx inspect --bootstrap > /dev/null 2>&1;
else
    docker buildx use penpot;
    docker buildx inspect --bootstrap  > /dev/null 2>&1;
fi

unset IFS;
docker buildx build --platform ${PLATFORM// /,} $OPTIONS -f Dockerfile.$IMAGE .;
