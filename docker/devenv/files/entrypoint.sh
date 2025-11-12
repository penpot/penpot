#!/usr/bin/env bash

set -e

EMSDK_QUIET=1 . /opt/emsdk/emsdk_env.sh;

usermod -u ${EXTERNAL_UID:-1000} penpot;

cp /root/.bashrc /home/penpot/.bashrc
cp /root/.vimrc /home/penpot/.vimrc
cp /root/.tmux.conf /home/penpot/.tmux.conf

chown penpot:users /home/penpot
rsync -ar --chown=penpot:users /opt/cargo/ /home/penpot/.cargo/

export PATH="/home/penpot/.cargo/bin:$PATH"
export CARGO_HOME="/home/penpot/.cargo"

exec "$@"
