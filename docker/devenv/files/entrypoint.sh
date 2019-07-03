#!/usr/bin/env zsh
set -ex
sudo pg_ctlcluster 11 main start

exec "$@"
