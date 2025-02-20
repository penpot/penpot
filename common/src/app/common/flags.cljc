;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.flags
  "Flags parsing algorithm."
  (:require
   [clojure.set :as set]
   [cuerdas.core :as str]))

(def login
  "Flags related to login features"
  #{;; Allows registration with login / password
    ;; if disabled, it's still possible to register/login with providers
    :registration
    ;; Redundant flag. TODO: remove it
    :login
    ;; enables the section of Access Tokens on profile.
    :access-tokens
    ;; Uses email and password as credentials.
    :login-with-password
    ;; Uses Github authentication as credentials.
    :login-with-github
    ;; Uses GitLab authentication as credentials.
    :login-with-gitlab
    ;; Uses Google/Gmail authentication as credentials.
    :login-with-google
    ;; Uses LDAP authentication as credentials.
    :login-with-ldap
    ;; Uses any generic authentication provider that implements OIDC protocol as credentials.
    :login-with-oidc
    ;; Allows registration with Open ID
    :oidc-registration
    ;; This logs to console the invitation tokens. It's useful in case the SMTP is not configured.
    :log-invitation-tokens})

(def email
  "Flags related to email features"
  #{;; Uses the domains in whitelist as the only allowed domains to register in the application.
    ;; Used with PENPOT_REGISTRATION_DOMAIN_WHITELIST
    :email-whitelist
    ;; Prevents the domains in blacklist to register in the application.
    ;; Used with PENPOT_REGISTRATION_DOMAIN_BLACKLIST
    :email-blacklist
    ;; Skips the email verification process. Not recommended for production environments.
    :email-verification
    ;; Only used if SMTP is disabled. Logs the emails into the console.
    :log-emails
    ;; Enable it to configure email settings.
    :smtp
    ;; Enables the debug mode of the SMTP library.
    :smtp-debug})

(def varia
  "Rest of the flags"
  #{:audit-log
    :audit-log-archive
    :audit-log-gc
    :auto-file-snapshot
    ;; enables the `/api/doc` endpoint that lists all the rpc methods available.
    :backend-api-doc
    ;; TODO: remove it and use only `backend-api-doc` flag
    :backend-openapi-doc
    ;; Disable it to start the RPC without the worker.
    :backend-worker
    ;; Only for development
    :component-thumbnails
    ;; enables the default cors configuration that allows all domains (currently this configuration is only used for development).
    :cors
    ;; Enables the templates dialog on Penpot dashboard.
    :dashboard-templates-section
    ;; disabled by default. When enabled, Penpot create demo users with a 7 days expiration.
    :demo-users
    ;; disabled by default. When enabled, it displays a warning that this is a test instance and data will be deleted periodically.
    :demo-warning
    ;; Activates the schema validation during update file.
    :file-schema-validation
    ;; Reports the schema validation errors internally.
    :soft-file-schema-validation
    ;; Activates the referential integrity validation during update file; related to components-v2.
    :file-validation
    ;; Reports the referential integrity validation errors internally.
    :soft-file-validation
    ;; TODO: deprecate this flag and consolidate the code
    :frontend-svgo
    ;; TODO: deprecate this flag and consolidate the code
    :exporter-svgo
    ;; TODO: deprecate this flag and consolidate the code
    :backend-svgo
    ;; If enabled, it makes the Google Fonts available.
    :google-fonts-provider
    ;; Only for development.
    :nrepl-server
    ;; Interactive repl. Only for development.
    :urepl-server
    ;; Programatic access to the runtime, used in administrative tasks.
    ;; It's mandatory to enable it to use the `manage.py` script.
    :prepl-server
    ;; Shows the onboarding modals right after registration.
    :onboarding
    :quotes
    :soft-quotes
    ;; Concurrency limit.
    :rpc-climit
    ;; Rate limit.
    :rpc-rlimit
    ;; Soft rate limit.
    :soft-rpc-rlimit
    ;; Disable it if you want to serve Penpot under a different domain than `http://localhost` without HTTPS.
    :secure-session-cookies
    ;; If `cors` enabled, this is ignored.
    :strict-session-cookies
    :telemetry
    :terms-and-privacy-checkbox
    ;; Only for developtment.
    :tiered-file-data-storage
    :transit-readable-response
    :user-feedback
    ;; TODO: remove this flag.
    :v2-migration
    :webhooks
    ;; TODO: deprecate this flag and consolidate the code
    :export-file-v3
    :render-wasm-dpr
    :hide-release-modal})

(def all-flags
  (set/union email login varia))

(def default
  "Flags with default configuration"
  [:enable-registration
   :enable-login-with-password
   :enable-export-file-v3
   :enable-frontend-svgo
   :enable-exporter-svgo
   :enable-backend-svgo
   :enable-backend-api-doc
   :enable-backend-openapi-doc
   :enable-backend-worker
   :enable-secure-session-cookies
   :enable-email-verification
   :enable-onboarding
   :enable-dashboard-templates-section
   :enable-google-fonts-provider
   :enable-component-thumbnails])

(defn parse
  [& flags]
  (loop [flags  (apply concat flags)
         result #{}]
    (let [item (first flags)]
      (if (nil? item)
        result
        (let [sname (name item)]
          (cond
            (str/starts-with? sname "enable-")
            (recur (rest flags)
                   (conj result (keyword (subs sname 7))))

            (str/starts-with? sname "disable-")
            (recur (rest flags)
                   (disj result (keyword (subs sname 8))))

            :else
            (recur (rest flags) result)))))))
