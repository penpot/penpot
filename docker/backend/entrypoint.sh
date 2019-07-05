#!/usr/bin/env bash
set -e

echo "Synchronize static data..."
rsync -avr --delete ./resources/public/static/ ./data/static/

echo "Setting up UXBOX Backend..."
exec "$@"
