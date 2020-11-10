#!/usr/bin/env bash

set -e

sudo cp /root/.bashrc /home/penpot/.bashrc
sudo cp /root/.vimrc /home/penpot/.vimrc
sudo cp /root/.tmux.conf /home/penpot/.tmux.conf

source /home/penpot/.bashrc
sudo chown penpot:users /home/penpot

exec "$@"
