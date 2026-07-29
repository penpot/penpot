#!/usr/bin/env bash

set -e

EMSDK_QUIET=1 . /opt/emsdk/emsdk_env.sh;

usermod -u ${EXTERNAL_UID:-1000} penpot;

cp /root/.bashrc /home/penpot/.bashrc
cp /root/.vimrc /home/penpot/.vimrc
cp /root/.tmux.conf /home/penpot/.tmux.conf

# Seed SERENA_HOME with default config on first run
mkdir -p ${SERENA_HOME}
if [ ! -f "${SERENA_HOME}/serena_config.yml" ]; then
    cp /home/serena_config.yml "${SERENA_HOME}/serena_config.yml"
fi
chown -R penpot:users ${SERENA_HOME}

chown penpot:users /home/penpot
# we need to be able to install rust-analyzer and possibly other dependencies with rustup
chown -R penpot:ubuntu /opt/rustup

rsync -ar --chown=penpot:users /opt/cargo/ /home/penpot/.cargo/

export JAVA_OPTS="-Djava.net.preferIPv4Stack=true"
export PATH="/home/penpot/.cargo/bin:$PATH"
export CARGO_HOME="/home/penpot/.cargo"

export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export COLORTERM=truecolor

exec "$@"
