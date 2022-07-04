#!/usr/bin/env bash

log() {
  echo "[$(date +%Y-%m-%dT%H:%M:%S%:z)] $*"
}

#########################################
## App Frontend config
#########################################

update_flags() {
  if [ -n "$PENPOT_FLAGS" ]; then
    sed -i \
      -e "s|^//var penpotFlags = .*;|var penpotFlags = \"$PENPOT_FLAGS\";|g" \
      "$1"
  fi
}

update_flags /var/www/app/js/config.js
exec "$@";
