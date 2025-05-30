#!/usr/bin/env bash

set -e

usermod -u ${EXTERNAL_UID:-1000} penpot;
chown -R penpot:users /home/cargo;

exec "$@"
