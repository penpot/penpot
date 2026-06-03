#!/usr/bin/env bash

sudo chown penpot:users /home/penpot

cd ~;

source ~/.bashrc

PENPOT_TMUX_SESSION="penpot"
PENPOT_TMUX_ATTACH="${PENPOT_TMUX_ATTACH:-true}"

function attach_or_exit() {
    if [ "$PENPOT_TMUX_ATTACH" = "true" ]; then
        exec tmux -2 attach-session -t "$PENPOT_TMUX_SESSION"
    fi

    echo "[start-tmux.sh] tmux session '$PENPOT_TMUX_SESSION' is running detached"
    exit 0
}

if tmux has-session -t "$PENPOT_TMUX_SESSION" 2>/dev/null; then
    echo "[start-tmux.sh] Reusing existing tmux session '$PENPOT_TMUX_SESSION'"
    attach_or_exit
fi

echo "[start-tmux.sh] Installing node dependencies"
pushd ~/penpot/frontend/
./scripts/setup;
popd
pushd ~/penpot/exporter/
./scripts/setup;
popd

tmux -2 new-session -d -s "$PENPOT_TMUX_SESSION"

tmux rename-window -t "$PENPOT_TMUX_SESSION:0" 'frontend watch'
tmux select-window -t "$PENPOT_TMUX_SESSION:0"
tmux send-keys -t "$PENPOT_TMUX_SESSION" 'cd penpot/frontend' enter C-l
tmux send-keys -t "$PENPOT_TMUX_SESSION" './scripts/watch app' enter

tmux new-window -t "$PENPOT_TMUX_SESSION:1" -n 'frontend storybook'
tmux select-window -t "$PENPOT_TMUX_SESSION:1"
tmux send-keys -t "$PENPOT_TMUX_SESSION" 'cd penpot/frontend' enter C-l
tmux send-keys -t "$PENPOT_TMUX_SESSION" './scripts/watch storybook' enter

tmux new-window -t "$PENPOT_TMUX_SESSION:2" -n 'exporter'
tmux select-window -t "$PENPOT_TMUX_SESSION:2"
tmux send-keys -t "$PENPOT_TMUX_SESSION" 'cd penpot/exporter' enter C-l
tmux send-keys -t "$PENPOT_TMUX_SESSION" 'rm -f target/app.js*' enter C-l
tmux send-keys -t "$PENPOT_TMUX_SESSION" './scripts/watch' enter

tmux split-window -v -t "$PENPOT_TMUX_SESSION"
tmux send-keys -t "$PENPOT_TMUX_SESSION" 'cd penpot/exporter' enter C-l
tmux send-keys -t "$PENPOT_TMUX_SESSION" './scripts/wait-and-start.sh' enter

tmux new-window -t "$PENPOT_TMUX_SESSION:3" -n 'backend'
tmux select-window -t "$PENPOT_TMUX_SESSION:3"
tmux send-keys -t "$PENPOT_TMUX_SESSION" 'cd penpot/backend' enter C-l
tmux send-keys -t "$PENPOT_TMUX_SESSION" './scripts/start-dev' enter

if echo "$PENPOT_FLAGS" | grep -q "enable-mcp"; then
    pushd ~/penpot/mcp/
    ./scripts/setup;
    pnpm run build;
    popd

    tmux new-window -t "$PENPOT_TMUX_SESSION:4" -n 'mcp'
    tmux select-window -t "$PENPOT_TMUX_SESSION:4"
    tmux send-keys -t "$PENPOT_TMUX_SESSION" 'cd penpot/mcp' enter C-l
    tmux send-keys -t "$PENPOT_TMUX_SESSION" './scripts/start-mcp-devenv' enter
fi

if [ "${SERENA_ENABLED:-false}" = "true" ]; then
    if [ -n "${SERENA_UPDATE_VERSION}" ]; then
        # update Serena (use sudo since the initial Serena installation is global; see Dockerfile)
        sudo -E uv tool install -p 3.13 serena-agent@${SERENA_UPDATE_VERSION} --prerelease=allow
    fi
    tmux new-window -t "$PENPOT_TMUX_SESSION:5" -n 'serena'
    tmux select-window -t "$PENPOT_TMUX_SESSION:5"
    tmux send-keys -t "$PENPOT_TMUX_SESSION" "serena start-mcp-server --transport streamable-http --port 14281 --project penpot --context ${SERENA_CONTEXT} --host 0.0.0.0" enter
fi

attach_or_exit
