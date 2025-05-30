#!/usr/bin/env bash

cp /root/.bashrc /home/penpot/.bashrc
cp /root/.vimrc /home/penpot/.vimrc
cp /root/.tmux.conf /home/penpot/.tmux.conf
chown -R penpot:users /home/penpot
chown -R penpot:users /home/cargo;

set -e
nginx
tail -f /dev/null
