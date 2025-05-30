#!/usr/bin/env bash

set -e

rsync -avr /usr/local/cargo/ /home/penpot/.cargo/

usermod -u ${EXTERNAL_UID:-1000} penpot

exec "$@"
