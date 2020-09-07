#!/usr/bin/env bash

source /home/uxbox/.bashrc

set -ex

sudo cp /root/.bashrc /home/uxbox/.bashrc
sudo cp /root/.vimrc /home/uxbox/.vimrc
sudo cp /root/.tmux.conf /home/uxbox/.tmux.conf

sudo chown uxbox:users /home/uxbox

exec "$@"
