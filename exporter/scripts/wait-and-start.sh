#!/usr/bin/env bash

SCRIPT_DIR=$(dirname $0);
source $SCRIPT_DIR/../../backend/scripts/_env;

bb -i '(babashka.wait/wait-for-port "localhost" 9630)';
bb -i '(babashka.wait/wait-for-path "target/app.js")';
sleep 2;

exec node target/app.js
