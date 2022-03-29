#!/usr/bin/env bash

export PATH=/usr/lib/jvm/openjdk/bin:/usr/local/nodejs/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/snap/bin

set -e
usermod -u ${EXTERNAL_UID:-1000} penpot

exec "$@"
