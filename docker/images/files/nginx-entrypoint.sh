#!/usr/bin/env bash

log() {
  echo "[$(date +%Y-%m-%dT%H:%M:%S%:z)] $*"
}


#########################################
## App Frontend config
#########################################

update_google_client_id() {
  if [ -n "$PENPOT_GOOGLE_CLIENT_ID" ]; then
    log "Updating Google Client Id: $PENPOT_GOOGLE_CLIENT_ID"
    sed -i \
      -e "s|^//var penpotGoogleClientID = \".*\";|var penpotGoogleClientID = \"$PENPOT_GOOGLE_CLIENT_ID\";|g" \
      "$1"
  fi
}


update_gitlab_client_id() {
  if [ -n "$PENPOT_GITLAB_CLIENT_ID" ]; then
    log "Updating GitLab Client Id: $PENPOT_GITLAB_CLIENT_ID"
    sed -i \
      -e "s|^//var penpotGitlabClientID = \".*\";|var penpotGitlabClientID = \"$PENPOT_GITLAB_CLIENT_ID\";|g" \
      "$1"
  fi
}


update_github_client_id() {
  if [ -n "$PENPOT_GITHUB_CLIENT_ID" ]; then
    log "Updating GitHub Client Id: $PENPOT_GITHUB_CLIENT_ID"
    sed -i \
      -e "s|^//var penpotGithubClientID = \".*\";|var penpotGithubClientID = \"$PENPOT_GITHUB_CLIENT_ID\";|g" \
      "$1"
  fi
}

update_oidc_client_id() {
  if [ -n "$PENPOT_OIDC_CLIENT_ID" ]; then
    log "Updating Oidc Client Id: $PENPOT_OIDC_CLIENT_ID"
    sed -i \
      -e "s|^//var penpotOIDCClientID = \".*\";|var penpotOIDCClientID = \"$PENPOT_OIDC_CLIENT_ID\";|g" \
      "$1"
  fi
}

# DEPRECATED
update_login_with_ldap() {
  if [ -n "$PENPOT_LOGIN_WITH_LDAP" ]; then
    log "Updating Login with LDAP: $PENPOT_LOGIN_WITH_LDAP"
    sed -i \
      -e "s|^//var penpotLoginWithLDAP = .*;|var penpotLoginWithLDAP = $PENPOT_LOGIN_WITH_LDAP;|g" \
      "$1"
  fi
}

# DEPRECATED
update_registration_enabled() {
  if [ -n "$PENPOT_REGISTRATION_ENABLED" ]; then
    log "Updating Registration Enabled: $PENPOT_REGISTRATION_ENABLED"
    sed -i \
      -e "s|^//var penpotRegistrationEnabled = .*;|var penpotRegistrationEnabled = $PENPOT_REGISTRATION_ENABLED;|g" \
      "$1"
  fi
}

update_flags() {
  if [ -n "$PENPOT_FLAGS" ]; then
    sed -i \
      -e "s|^//var penpotFlags = .*;|var penpotFlags = \"$PENPOT_FLAGS\";|g" \
      "$1"
  fi
}

update_google_client_id /var/www/app/js/config.js
update_gitlab_client_id /var/www/app/js/config.js
update_github_client_id /var/www/app/js/config.js
update_oidc_client_id /var/www/app/js/config.js
update_login_with_ldap /var/www/app/js/config.js
update_registration_enabled /var/www/app/js/config.js
update_flags /var/www/app/js/config.js
exec "$@";
