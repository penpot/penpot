#!/usr/bin/env bash

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


#########################################
## Nginx Config
#########################################

export PENPOT_BACKEND_URI=${PENPOT_BACKEND_URI:-http://penpot-backend:6060};
export PENPOT_EXPORTER_URI=${PENPOT_EXPORTER_URI:-http://penpot-exporter:6061};
export PENPOT_INTERNAL_RESOLVER=${PENPOT_INTERNAL_RESOLVER:-127.0.0.11};

envsubst "\$PENPOT_BACKEND_URI,\$PENPOT_EXPORTER_URI,\$PENPOT_INTERNAL_RESOLVER" < /etc/nginx/nginx.conf.template > /etc/nginx/nginx.conf

exec "$@";
