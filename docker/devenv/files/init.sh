#!/usr/bin/env zsh

set -e;
source ~/.zshrc

echo "[init.sh] Setting up local permissions."
sudo chown -R uxbox /home/uxbox/local

echo "[init.sh] Installing node dependencies"
pushd /home/uxbox/uxbox/frontend/
npm ci
popd

echo "[init.sh] Ready!"
tail -f /dev/null
