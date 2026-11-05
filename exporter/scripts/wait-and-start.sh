#!/usr/bin/env bash

SCRIPT_DIR=$(dirname $0);
source $SCRIPT_DIR/../../backend/scripts/_env;

if [ -f $SCRIPT_DIR/../../backend/scripts/_env.local ]; then
    source $SCRIPT_DIR/../../backend/scripts/_env.local;
fi

# In-container address of the platform edge (nginx). Invariant across
# instances (per-container loopback), so it lives here, not in compose
# or _env; an explicitly exported value always wins.
export PENPOT_INTERNAL_URI="${PENPOT_INTERNAL_URI:-http://localhost:4449}"

bb -i '(babashka.wait/wait-for-port "localhost" 9630)';
bb -i '(babashka.wait/wait-for-path "target/app.js")';
sleep 2;

exec node target/app.js
