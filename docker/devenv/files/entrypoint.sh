#!/usr/bin/env bash

set -e

sudo cp /root/.bashrc /home/uxbox/.bashrc
sudo cp /root/.vimrc /home/uxbox/.vimrc
sudo cp /root/.tmux.conf /home/uxbox/.tmux.conf

source /home/uxbox/.bashrc
sudo chown uxbox:users /home/uxbox

exec "$@"
