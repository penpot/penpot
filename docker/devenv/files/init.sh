#!/usr/bin/env bash

set -e;
source ~/.bashrc

echo "[init.sh] Start nginx."
sudo nginx

echo "[init.sh] Ready!"
tail -f /dev/null
