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
PENPOT_DEFAULT_INTERNAL_RESOLVER="$(awk 'BEGIN{ORS=" "} $1=="nameserver" { sub(/%.*$/,"",$2); print ($2 ~ ":")? "["$2"]": $2}' /etc/resolv.conf)";
export PENPOT_INTERNAL_RESOLVER=${PENPOT_INTERNAL_RESOLVER:-$PENPOT_DEFAULT_INTERNAL_RESOLVER};
export PENPOT_HTTP_SERVER_MAX_MULTIPART_BODY_SIZE=${PENPOT_HTTP_SERVER_MAX_MULTIPART_BODY_SIZE:-367001600}; # Default to 350MiB

envsubst "\$PENPOT_BACKEND_URI,\$PENPOT_EXPORTER_URI,\$PENPOT_HTTP_SERVER_MAX_MULTIPART_BODY_SIZE" \
         < /etc/nginx/nginx.conf.template > /etc/nginx/nginx.conf;

envsubst "\$PENPOT_INTERNAL_RESOLVER" \
         < /etc/nginx/overrides.d/resolvers.conf.template > /etc/nginx/overrides.d/resolvers.conf;

exec "$@";
