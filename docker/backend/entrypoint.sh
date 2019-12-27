#!/usr/bin/env bash
set -e

echo "Synchronize static data..."
rsync -avr --delete ./resources/public/static/ ./data/static/

if [ -z "$UXBOX_DATABASE_URI" ]; then
    echo "Initializing database connection string..."
    UXBOX_DATABASE_URI="\"postgresql://$(echo ${UXBOX_DATABASE_SERVER} | tr -d '"'):${UXBOX_DATABASE_PORT}/$(echo ${UXBOX_DATABASE_NAME} | tr -d '"')\""
    echo "Database connection string: $UXBOX_DATABASE_URI"
fi

echo "Setting up UXBOX Backend..."
exec "$@"
