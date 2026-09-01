#!/usr/bin/env bash

is_truthy() {
  local value="${1,,}"
  [[ "$value" == "true" || "$value" == "t" || "$value" == "1" ]]
}

is_falsy() {
  local value="${1,,}"
  [[ "$value" == "false" || "$value" == "f" || "$value" == "0" ]]
}


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
  if [ -n "$PENPOT_FLAGS" ]; then
    echo "$(sed \
      -e "s|^//var penpotFlags = .*;|var penpotFlags = \"$PENPOT_FLAGS\";|g" \
      "$1")" > "$1"
  fi

  if [ -n "$PENPOT_PUBLIC_URI" ]; then
      echo "var penpotPublicURI = \"$PENPOT_PUBLIC_URI\";" >> "$1";
  fi
}

update_oidc_name() {
  if [ -n "$PENPOT_OIDC_NAME" ]; then
    echo "$(sed \
      -e "s|^//var penpotOIDCName = .*;|var penpotOIDCName = \"$PENPOT_OIDC_NAME\";|g" \
      "$1")" > "$1"
  fi
}

update_flags /var/www/app/js/config.js
update_oidc_name /var/www/app/js/config.js

#########################################
## Nginx Config
#########################################

export PENPOT_BACKEND_URI=${PENPOT_BACKEND_URI:-http://penpot-backend:6060}
export PENPOT_EXPORTER_URI=${PENPOT_EXPORTER_URI:-http://penpot-exporter:6061}
export PENPOT_HTTP_SERVER_MAX_BODY_SIZE=${PENPOT_HTTP_SERVER_MAX_BODY_SIZE:-367001600} # Default to 350MiB
export PENPOT_IPV6_LISTEN_DIRECTIVE=${PENPOT_IPV6_LISTEN_DIRECTIVE:-"listen [::]:8080 default_server reuseport backlog=16384;"}
if is_truthy "${PENPOT_DISABLE_IPV6_LISTEN:-}"; then
  export PENPOT_IPV6_LISTEN_DIRECTIVE=""
fi
envsubst "\$PENPOT_BACKEND_URI,\$PENPOT_EXPORTER_URI,\$PENPOT_HTTP_SERVER_MAX_BODY_SIZE,\$PENPOT_IPV6_LISTEN_DIRECTIVE" \
        < /tmp/nginx.conf.template > /etc/nginx/nginx.conf

if [[ $PENPOT_FLAGS == *"enable-admin-console"* ]]; then
    export PENPOT_ADMIN_CONSOLE_URI=${PENPOT_ADMIN_CONSOLE_URI:-http://penpot-admin-console:3000}
    envsubst "\$PENPOT_ADMIN_CONSOLE_URI" \
             < /tmp/nginx-admin-console-locations.conf.template > /etc/nginx/overrides/server.d/admin-console-locations.conf
else
    rm -f /etc/nginx/overrides/server.d/admin-console-locations.conf
fi

if [[ $PENPOT_FLAGS == *"enable-mcp"* ]]; then
    export PENPOT_MCP_URI=${PENPOT_MCP_URI:-http://penpot-mcp:4401}
    export PENPOT_MCP_URI_WS=${PENPOT_MCP_URI_WS:-http://penpot-mcp:4402}

    envsubst "\$PENPOT_MCP_URI,\$PENPOT_MCP_URI_WS" \
             < /tmp/nginx-mcp-locations.conf.template > /etc/nginx/overrides/server.d/mcp-locations.conf
else
    rm -f /etc/nginx/overrides/server.d/mcp-locations.conf
fi

PENPOT_DEFAULT_INTERNAL_RESOLVER="$(awk 'BEGIN{ORS=" "} $1=="nameserver" { sub(/%.*$/,"",$2); print ($2 ~ ":")? "["$2"]": $2}' /etc/resolv.conf)"
export PENPOT_INTERNAL_RESOLVER=${PENPOT_INTERNAL_RESOLVER:-$PENPOT_DEFAULT_INTERNAL_RESOLVER}
envsubst "\$PENPOT_INTERNAL_RESOLVER" \
         < /tmp/resolvers.conf.template > /etc/nginx/overrides/http.d/resolvers.conf

#########################################
## Security Headers Config
#########################################

# The default policy describes what a stock Penpot deployment actually
# needs: 'wasm-unsafe-eval' for the render engine, 'unsafe-inline' styles
# for the inline style attributes emitted by the UI, and blob:/data: for
# thumbnails, exports and font handling. Everything else is same-origin,
# because the Google Fonts and GitHub templates endpoints are reverse
# proxied by this very server.
#
# The hashes of the inline scripts of index.html are emitted by the frontend
# build and moved to /etc/nginx at image build time. A bundle predating that
# change simply yields no hashes, in which case those scripts would be
# reported (or blocked under enforce) as before.
#
# It ships in report-only mode because deployments with plugins enabled still
# report eval and remote fetch violations from the SES sandbox. Enforcing mode
# stays opt-in until that is resolved.
export PENPOT_CSP_MODE=${PENPOT_CSP_MODE:-report-only}

if [ -r /etc/nginx/csp-script-hashes.txt ]; then
    PENPOT_CSP_SCRIPT_HASHES=" $(tr -d '\n' < /etc/nginx/csp-script-hashes.txt)"
else
    PENPOT_CSP_SCRIPT_HASHES=""
fi

# Remember whether the policy comes from the deployment before the default
# is applied, so the warning below only fires for the default one.
if [ -n "${PENPOT_CSP_POLICY:-}" ]; then
    PENPOT_CSP_POLICY_IS_CUSTOM="true"
else
    PENPOT_CSP_POLICY_IS_CUSTOM="false"
fi

export PENPOT_CSP_POLICY=${PENPOT_CSP_POLICY:-"default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'self'; form-action 'self'; script-src 'self' 'wasm-unsafe-eval'${PENPOT_CSP_SCRIPT_HASHES}; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self'; connect-src 'self' blob: data:; worker-src 'self' blob:; media-src 'self' blob:; frame-src 'self'; manifest-src 'self'"}

case "${PENPOT_CSP_MODE}" in
    enforce)
        export PENPOT_CSP_DIRECTIVE="add_header Content-Security-Policy \"${PENPOT_CSP_POLICY}\" always;"
        if [ "${PENPOT_CSP_POLICY_IS_CUSTOM}" = "false" ]; then
            echo "penpot: WARNING: PENPOT_CSP_MODE=enforce is not supported with the default policy yet." >&2
            echo "penpot: deployments using plugins will break, because the plugin sandbox requires 'unsafe-eval'." >&2
            echo "penpot: set PENPOT_CSP_POLICY to your own policy, or keep the default report-only mode." >&2
        fi
        ;;
    report-only)
        export PENPOT_CSP_DIRECTIVE="add_header Content-Security-Policy-Report-Only \"${PENPOT_CSP_POLICY}\" always;"
        ;;
    disabled)
        export PENPOT_CSP_DIRECTIVE=""
        ;;
    *)
        echo "penpot: invalid PENPOT_CSP_MODE '${PENPOT_CSP_MODE}'; expected one of: enforce, report-only, disabled" >&2
        exit 1
        ;;
esac

# HSTS is only meaningful when the deployment is served over HTTPS, so it
# defaults to enabled when PENPOT_PUBLIC_URI declares an https scheme and
# to disabled otherwise. Set PENPOT_HSTS_VALUE explicitly to override it,
# for example to add includeSubDomains or preload, or to an empty value
# to disable it on an https deployment.
if [[ "${PENPOT_PUBLIC_URI:-}" == https://* ]]; then
    export PENPOT_HSTS_VALUE=${PENPOT_HSTS_VALUE-"max-age=31536000"}
else
    export PENPOT_HSTS_VALUE=${PENPOT_HSTS_VALUE-""}
fi

if [ -n "${PENPOT_HSTS_VALUE}" ]; then
    export PENPOT_HSTS_DIRECTIVE="add_header Strict-Transport-Security \"${PENPOT_HSTS_VALUE}\" always;"
else
    export PENPOT_HSTS_DIRECTIVE=""
fi

envsubst "\$PENPOT_CSP_DIRECTIVE,\$PENPOT_HSTS_DIRECTIVE" \
         < /tmp/nginx-security-headers.conf.template > /etc/nginx/nginx-security-headers.conf

exec "$@";
