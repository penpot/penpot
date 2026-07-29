#!/usr/bin/env bash

set -e
nginx
caddy start -c /home/Caddyfile
tail -f /dev/null;
