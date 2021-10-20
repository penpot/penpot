#!/usr/bin/env bash

sudo chown penpot:users /home/penpot

cd ~;

source ~/.bashrc

set -e;

echo "[start-tmux.sh] Installing node dependencies"
pushd ~/penpot/exporter/
yarn install
popd

tmux -2 new-session -d -s penpot

tmux rename-window -t penpot:0 'exporter'
tmux select-window -t penpot:0
tmux send-keys -t penpot 'cd penpot/exporter' enter C-l
tmux send-keys -t penpot 'rm -f target/app.js*' enter C-l
tmux send-keys -t penpot 'clojure -M:dev:shadow-cljs watch main' enter

tmux split-window -v
tmux send-keys -t penpot 'cd penpot/exporter' enter C-l
tmux send-keys -t penpot './scripts/wait-and-start.sh' enter

tmux new-window -t penpot:1 -n 'backend'
tmux select-window -t penpot:1
tmux send-keys -t penpot 'cd penpot/backend' enter C-l
tmux send-keys -t penpot './scripts/start-dev' enter

tmux -2 attach-session -t penpot
