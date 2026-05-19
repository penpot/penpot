#!/usr/bin/env bash

sudo chown penpot:users /home/penpot

cd ~;

source ~/.bashrc

echo "[start-tmux.sh] Installing node dependencies"
pushd ~/penpot/frontend/
./scripts/setup;
popd
pushd ~/penpot/exporter/
./scripts/setup;
popd

tmux -2 new-session -d -s penpot

tmux rename-window -t penpot:0 'frontend watch'
tmux select-window -t penpot:0
tmux send-keys -t penpot 'cd penpot/frontend' enter C-l
tmux send-keys -t penpot './scripts/watch app' enter

tmux new-window -t penpot:1 -n 'frontend storybook'
tmux select-window -t penpot:1
tmux send-keys -t penpot 'cd penpot/frontend' enter C-l
tmux send-keys -t penpot './scripts/watch storybook' enter

tmux new-window -t penpot:2 -n 'exporter'
tmux select-window -t penpot:2
tmux send-keys -t penpot 'cd penpot/exporter' enter C-l
tmux send-keys -t penpot 'rm -f target/app.js*' enter C-l
tmux send-keys -t penpot './scripts/watch' enter

tmux split-window -v
tmux send-keys -t penpot 'cd penpot/exporter' enter C-l
tmux send-keys -t penpot './scripts/wait-and-start.sh' enter

tmux new-window -t penpot:3 -n 'backend'
tmux select-window -t penpot:3
tmux send-keys -t penpot 'cd penpot/backend' enter C-l
tmux send-keys -t penpot './scripts/start-dev' enter

if echo "$PENPOT_FLAGS" | grep -q "enable-mcp"; then
    pushd ~/penpot/mcp/
    ./scripts/setup;
    pnpm run build;
    popd

    tmux new-window -t penpot:4 -n 'mcp'
    tmux select-window -t penpot:4
    tmux send-keys -t penpot 'cd penpot/mcp' enter C-l
    tmux send-keys -t penpot './scripts/start-mcp-devenv' enter
fi


# update Serena (use sudo since the initial Serena installation is global; see Dockerfile)
if [ -n "${SERENA_UPDATE_VERSION}" ]; then
    sudo -E uv tool install -p 3.13 serena-agent@${SERENA_UPDATE_VERSION} --prerelease=allow
fi

if [ "${SERENA_ENABLED:-false}" = "true" ]; then
    tmux new-window -t penpot:5 -n 'serena'
    tmux select-window -t penpot:5
    tmux send-keys -t penpot "serena start-mcp-server --transport streamable-http --port 14281 --project penpot --context ${SERENA_CONTEXT} --host 0.0.0.0" enter
fi

tmux -2 attach-session -t penpot
