#!/usr/bin/env zsh

set -e;
source ~/.zshrc

echo "[init.sh] Start nginx."
sudo nginx

echo "[init.sh] Setting up local permissions."
sudo chown -R uxbox /home/uxbox/local

echo "[init.sh] Ready!"
tail -f /dev/null
