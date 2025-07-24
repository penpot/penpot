#!/usr/bin/env bash
set -x

IMAGE=${1:-backend}

OUTPUT="type=registry"

if [ "--local" = "$2" ]; then
    OUTPUT="type=docker"
fi

ORG=${PENPOT_DOCKER_NAMESPACE:-penpotapp};
PLATFORM=${PENPOT_BUILD_PLATFORM:-linux/amd64,linux/arm64};
VERSION=${PENPOT_BUILD_VERSION:-latest}
DOCKER_IMAGE="$ORG/$IMAGE";
OPTIONS="-t $DOCKER_IMAGE:$VERSION";

IFS=", "
read -a TAGS <<< $PENPOT_BUILD_TAGS;

for element in "${TAGS[@]}"; do
    OPTIONS="$OPTIONS -t $DOCKER_IMAGE:$element";
done

docker buildx inspect penpot > /dev/null 2>&1;
docker run --privileged --rm tonistiigi/binfmt --install all > /dev/null;

if [ $? -eq 1 ]; then
    docker buildx create --name=penpot --use
    docker buildx inspect --bootstrap > /dev/null 2>&1;
else
    docker buildx use penpot;
    docker buildx inspect --bootstrap  > /dev/null 2>&1;
fi

unset IFS;

shift;
docker buildx build --output $OUTPUT --platform ${PLATFORM// /,} $OPTIONS -f Dockerfile.$IMAGE .;
