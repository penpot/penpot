#!/usr/bin/env bash

export PATH=/usr/lib/jvm/openjdk/bin:/usr/local/nodejs/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/snap/bin

source /usr/local/emsdk/emsdk_env.sh;
source /usr/local/cargo/env

export JAVA_OPTS=${JAVA_OPTS:-"-Xmx1000m -Xms200m"};

set -e
usermod -u ${EXTERNAL_UID:-1000} penpot

exec "$@"
