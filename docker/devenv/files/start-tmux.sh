#!/usr/bin/env bash

sudo cp /root/.bashrc /home/penpot/.bashrc
sudo cp /root/.vimrc /home/penpot/.vimrc
sudo cp /root/.tmux.conf /home/penpot/.tmux.conf

source /home/penpot/.bashrc
sudo chown penpot:users /home/penpot

cd ~;

set -e;
source ~/.bashrc

echo "[start-tmux.sh] Installing node dependencies"
pushd ~/penpot/frontend/
yarn install
popd
pushd ~/penpot/exporter/
yarn install
popd

tmux -2 new-session -d -s penpot

tmux new-window -t penpot:1 -n 'shadow watch'
tmux select-window -t penpot:1
tmux send-keys -t penpot 'cd penpot/frontend' enter C-l
tmux send-keys -t penpot 'npx shadow-cljs watch main' enter

tmux new-window -t penpot:2 -n 'exporter'
tmux select-window -t penpot:2
tmux send-keys -t penpot 'cd penpot/exporter' enter C-l
tmux send-keys -t penpot 'npx shadow-cljs watch main' enter
tmux split-window -v
tmux send-keys -t penpot 'cd penpot/exporter' enter C-l
tmux send-keys -t penpot './scripts/wait-and-start.sh' enter

tmux new-window -t penpot:3 -n 'backend'
tmux select-window -t penpot:3
tmux send-keys -t penpot 'cd penpot/backend' enter C-l
tmux send-keys -t penpot './scripts/start-dev' enter

tmux rename-window -t penpot:0 'gulp'
tmux select-window -t penpot:0
tmux send-keys -t penpot 'cd penpot/frontend' enter C-l
tmux send-keys -t penpot 'npx gulp --theme=${PENPOT_THEME} watch' enter

tmux -2 attach-session -t penpot
