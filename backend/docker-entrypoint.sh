#!/usr/bin/env bash
set -e

echo "UXBOX backend Docker entrypoint initialization..."

echo 'Running UXBOX backend'
exec "$@"
