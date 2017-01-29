#!/usr/bin/env bash

tmux -2 new-session -d -s uxbox

tmux new-window -t uxbox:1 -n 'figwheel'
tmux select-window -t uxbox:1
tmux send-keys -t uxbox 'cd uxbox/frontend' enter C-l
tmux send-keys -t uxbox 'npm run figwheel' enter

tmux new-window -t uxbox:2 -n 'backend'
tmux select-window -t uxbox:2
tmux send-keys -t uxbox 'cd uxbox/backend' enter C-l
tmux send-keys -t uxbox 'bash ./scripts/fixtures.sh' enter

tmux new-window -t uxbox:3 -n 'services'
tmux select-window -t uxbox:3
tmux send-keys -t uxbox 'sudo pg_ctlcluster 9.6 main start' enter

tmux rename-window -t uxbox:0 'gulp'
tmux select-window -t uxbox:0
tmux send-keys -t uxbox 'cd uxbox/frontend' enter C-l
tmux send-keys -t uxbox 'if [ ! -e ./node_modules ]; then npm install; fi' enter C-l
tmux send-keys -t uxbox 'npm run watch' enter

tmux -2 attach-session -t uxbox
