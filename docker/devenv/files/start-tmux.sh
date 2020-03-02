#!/usr/bin/env zsh

set -e;
source ~/.zshrc

echo "[start-tmux.sh] Installing node dependencies"
pushd ~/uxbox/frontend/
rm -rf node_modules;
npm ci;
popd

tmux -2 new-session -d -s uxbox

tmux new-window -t uxbox:1 -n 'figwheel'
tmux select-window -t uxbox:1
tmux send-keys -t uxbox 'cd uxbox/frontend' enter C-l
tmux send-keys -t uxbox 'clojure -Adev tools.clj figwheel' enter

tmux new-window -t uxbox:2 -n 'backend'
tmux select-window -t uxbox:2
tmux send-keys -t uxbox 'cd uxbox/backend' enter C-l
tmux send-keys -t uxbox './bin/start-dev' enter

tmux rename-window -t uxbox:0 'gulp'
tmux select-window -t uxbox:0
tmux send-keys -t uxbox 'cd uxbox/frontend' enter C-l
tmux send-keys -t uxbox 'npx gulp watch' enter

tmux -2 attach-session -t uxbox
