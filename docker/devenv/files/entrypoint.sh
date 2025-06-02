#!/usr/bin/env bash

set -e

EMSDK_QUIET=1 . /home/emsdk/emsdk_env.sh;

usermod -u ${EXTERNAL_UID:-1000} penpot;

cp /root/.bashrc /home/penpot/.bashrc
cp /root/.vimrc /home/penpot/.vimrc
cp /root/.tmux.conf /home/penpot/.tmux.conf

chown -R penpot:users /home/penpot

exec "$@"
