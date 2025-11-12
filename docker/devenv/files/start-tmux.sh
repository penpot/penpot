#!/usr/bin/env bash

sudo chown penpot:users /home/penpot

cd ~;

source ~/.bashrc

echo "[start-tmux.sh] Installing node dependencies"
pushd ~/penpot/frontend/
corepack install;
yarn install;
yarn playwright install chromium
popd
pushd ~/penpot/exporter/
corepack install;
yarn install
yarn playwright install chromium
popd

tmux -2 new-session -d -s penpot

tmux rename-window -t penpot:0 'frontend watch'
tmux select-window -t penpot:0
tmux send-keys -t penpot 'cd penpot/frontend' enter C-l
tmux send-keys -t penpot 'yarn run watch' enter

tmux new-window -t penpot:1 -n 'frontend shadow'
tmux select-window -t penpot:1
tmux send-keys -t penpot 'cd penpot/frontend' enter C-l
tmux send-keys -t penpot 'yarn run watch:app' enter

tmux new-window -t penpot:2 -n 'frontend storybook'
tmux select-window -t penpot:2
tmux send-keys -t penpot 'cd penpot/frontend' enter C-l
tmux send-keys -t penpot 'yarn run watch:storybook' enter

tmux new-window -t penpot:3 -n 'exporter'
tmux select-window -t penpot:3
tmux send-keys -t penpot 'cd penpot/exporter' enter C-l
tmux send-keys -t penpot 'rm -f target/app.js*' enter C-l
tmux send-keys -t penpot 'yarn run watch' enter

tmux split-window -v
tmux send-keys -t penpot 'cd penpot/exporter' enter C-l
tmux send-keys -t penpot './scripts/wait-and-start.sh' enter

tmux new-window -t penpot:4 -n 'backend'
tmux select-window -t penpot:4
tmux send-keys -t penpot 'cd penpot/backend' enter C-l
tmux send-keys -t penpot './scripts/start-dev' enter

tmux -2 attach-session -t penpot
