#!/usr/bin/env bash

set -ex

export PATH="/usr/local/node-v12.18.3/bin/:$PATH"
# yarn install

exec "$@"
