#!/usr/bin/env zsh
set -e;

echo "[init.sh] Setting up local permissions."
sudo chown -R uxbox /home/uxbox/local

echo "[init.sh] Ready!"
tail -f /dev/null
