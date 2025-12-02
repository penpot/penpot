#!/usr/bin/env bash

set -e
nginx
mkdir -p penpot/logs
caddy start -c /home/Caddyfile
tail -f /dev/null;
