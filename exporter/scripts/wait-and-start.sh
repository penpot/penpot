#!/usr/bin/env bash

bb -i '(babashka.wait/wait-for-port "localhost" 9630)';
bb -i '(babashka.wait/wait-for-path "target/app.js")';
sleep 2;
node target/app.js
