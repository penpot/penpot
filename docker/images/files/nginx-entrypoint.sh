#!/usr/bin/env bash

#########################################
## Air Gapped config
#########################################

if [[ $PENPOT_FLAGS == *"enable-air-gapped-conf"* ]]; then
    rm /etc/nginx/overrides/location.d/external-locations.conf;
    export PENPOT_FLAGS="$PENPOT_FLAGS disable-google-fonts-provider disable-dashboard-templates-section"
fi

#########################################
## App Frontend config
#########################################

update_flags() {
  local config_file="/var/www/app/js/config.js"

  if [ -n "$PENPOT_FLAGS" ]; then
    sed -i \
      -e "s|^//var penpotFlags = .*;|var penpotFlags = \"$PENPOT_FLAGS\";|g" \
      "$config_file"
  fi
}

append_extra_config() {
  local config_file="/var/www/app/js/config.js"
  local config_file_extra="/var/www/app/js/config.extra.js"

  if [ -f "$config_file_extra" ]; then
    cat "$config_file_extra" >> "$config_file"
  fi
}

update_flags
append_extra_config

#########################################
## Nginx Config
#########################################

export PENPOT_BACKEND_URI=${PENPOT_BACKEND_URI:-http://penpot-backend:6060}
export PENPOT_EXPORTER_URI=${PENPOT_EXPORTER_URI:-http://penpot-exporter:6061}
export PENPOT_HTTP_SERVER_MAX_MULTIPART_BODY_SIZE=${PENPOT_HTTP_SERVER_MAX_MULTIPART_BODY_SIZE:-367001600} # Default to 350MiB
envsubst "\$PENPOT_BACKEND_URI,\$PENPOT_EXPORTER_URI,\$PENPOT_HTTP_SERVER_MAX_MULTIPART_BODY_SIZE" \
         < /tmp/nginx.conf.template > /etc/nginx/nginx.conf

PENPOT_DEFAULT_INTERNAL_RESOLVER="$(awk 'BEGIN{ORS=" "} $1=="nameserver" { sub(/%.*$/,"",$2); print ($2 ~ ":")? "["$2"]": $2}' /etc/resolv.conf)"
export PENPOT_INTERNAL_RESOLVER=${PENPOT_INTERNAL_RESOLVER:-$PENPOT_DEFAULT_INTERNAL_RESOLVER}
envsubst "\$PENPOT_INTERNAL_RESOLVER" \
         < /tmp/resolvers.conf.template > /etc/nginx/overrides/http.d/resolvers.conf

exec "$@";
