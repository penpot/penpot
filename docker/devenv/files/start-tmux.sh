#!/usr/bin/env bash

cd ~;

set -e;
source ~/.bashrc

echo "[start-tmux.sh] Installing node dependencies"
pushd ~/uxbox/frontend/
yarn install
popd
pushd ~/uxbox/exporter/
yarn install
popd

tmux -2 new-session -d -s uxbox

tmux new-window -t uxbox:1 -n 'shadow watch'
tmux select-window -t uxbox:1
tmux send-keys -t uxbox 'cd uxbox/frontend' enter C-l
tmux send-keys -t uxbox 'npx shadow-cljs watch main' enter

tmux new-window -t uxbox:2 -n 'exporter'
tmux select-window -t uxbox:2
tmux send-keys -t uxbox 'cd uxbox/exporter' enter C-l
tmux send-keys -t uxbox 'npx shadow-cljs watch main' enter
tmux split-window -v
tmux send-keys -t uxbox 'cd uxbox/exporter' enter C-l
tmux send-keys -t uxbox './scripts/wait-and-start.sh' enter

tmux new-window -t uxbox:3 -n 'backend'
tmux select-window -t uxbox:3
tmux send-keys -t uxbox 'cd uxbox/backend' enter C-l
tmux send-keys -t uxbox './scripts/start-dev' enter

tmux rename-window -t uxbox:0 'gulp'
tmux select-window -t uxbox:0
tmux send-keys -t uxbox 'cd uxbox/frontend' enter C-l
tmux send-keys -t uxbox 'npx gulp --theme=${UXBOX_THEME} watch' enter

tmux -2 attach-session -t uxbox
