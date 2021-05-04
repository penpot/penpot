;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.config
  "A configuration management."
  (:refer-clojure :exclude [get])
  (:require
   [app.common.spec :as us]
   [app.common.version :as v]
   [app.util.time :as dt]
   [clojure.core :as c]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [environ.core :refer [env]]))

(prefer-method print-method
               clojure.lang.IRecord
               clojure.lang.IDeref)

(prefer-method pprint/simple-dispatch
               clojure.lang.IPersistentMap
               clojure.lang.IDeref)

(def defaults
  {:http-server-port 6060
   :host "devenv"
   :tenant "dev"
   :database-uri "postgresql://postgres/penpot"
   :database-username "penpot"
   :database-password "penpot"

   :default-blob-version 1

   :loggers-zmq-uri "tcp://localhost:45556"

   :asserts-enabled false

   :public-uri "http://localhost:3449"
   :redis-uri "redis://redis/0"

   :srepl-host "127.0.0.1"
   :srepl-port 6062

   :storage-backend :fs

   :storage-fs-directory "assets"
   :storage-s3-region :eu-central-1
   :storage-s3-bucket "penpot-devenv-assets-pre"

   :feedback-destination "info@example.com"
   :feedback-enabled false

   :assets-path "/internal/assets/"

   :rlimits-password 10
   :rlimits-image 2

   :smtp-enabled false
   :smtp-default-reply-to "Penpot <no-reply@example.com>"
   :smtp-default-from "Penpot <no-reply@example.com>"

   :profile-complaint-max-age (dt/duration {:days 7})
   :profile-complaint-threshold 2

   :profile-bounce-max-age (dt/duration {:days 7})
   :profile-bounce-threshold 10

   :allow-demo-users true
   :registration-enabled true
   :registration-domain-whitelist ""

   :telemetry-enabled false
   :telemetry-uri "https://telemetry.penpot.app/"

   :ldap-user-query "(|(uid=:username)(mail=:username))"
   :ldap-attrs-username "uid"
   :ldap-attrs-email "mail"
   :ldap-attrs-fullname "cn"
   :ldap-attrs-photo "jpegPhoto"

   ;; a server prop key where initial project is stored.
   :initial-project-skey "initial-project"
   })

(s/def ::secret-key ::us/string)
(s/def ::allow-demo-users ::us/boolean)
(s/def ::asserts-enabled ::us/boolean)
(s/def ::assets-path ::us/string)
(s/def ::database-password (s/nilable ::us/string))
(s/def ::database-uri ::us/string)
(s/def ::database-username (s/nilable ::us/string))
(s/def ::default-blob-version ::us/integer)
(s/def ::error-report-webhook ::us/string)
(s/def ::feedback-destination ::us/string)
(s/def ::feedback-enabled ::us/boolean)
(s/def ::feedback-token ::us/string)
(s/def ::github-client-id ::us/string)
(s/def ::github-client-secret ::us/string)
(s/def ::gitlab-base-uri ::us/string)
(s/def ::gitlab-client-id ::us/string)
(s/def ::gitlab-client-secret ::us/string)
(s/def ::google-client-id ::us/string)
(s/def ::google-client-secret ::us/string)
(s/def ::oidc-client-id ::us/string)
(s/def ::oidc-client-secret ::us/string)
(s/def ::oidc-base-uri ::us/string)
(s/def ::oidc-token-uri ::us/string)
(s/def ::oidc-auth-uri ::us/string)
(s/def ::oidc-user-uri ::us/string)
(s/def ::oidc-scopes ::us/set-of-str)
(s/def ::oidc-roles ::us/set-of-str)
(s/def ::oidc-roles-attr ::us/keyword)
(s/def ::host ::us/string)
(s/def ::http-server-port ::us/integer)
(s/def ::http-session-idle-max-age ::dt/duration)
(s/def ::http-session-updater-batch-max-age ::dt/duration)
(s/def ::http-session-updater-batch-max-size ::us/integer)
(s/def ::initial-project-skey ::us/string)
(s/def ::ldap-attrs-email ::us/string)
(s/def ::ldap-attrs-fullname ::us/string)
(s/def ::ldap-attrs-photo ::us/string)
(s/def ::ldap-attrs-username ::us/string)
(s/def ::ldap-base-dn ::us/string)
(s/def ::ldap-bind-dn ::us/string)
(s/def ::ldap-bind-password ::us/string)
(s/def ::ldap-host ::us/string)
(s/def ::ldap-port ::us/integer)
(s/def ::ldap-ssl ::us/boolean)
(s/def ::ldap-starttls ::us/boolean)
(s/def ::ldap-user-query ::us/string)
(s/def ::loggers-loki-uri ::us/string)
(s/def ::loggers-zmq-uri ::us/string)
(s/def ::media-directory ::us/string)
(s/def ::media-uri ::us/string)
(s/def ::profile-bounce-max-age ::dt/duration)
(s/def ::profile-bounce-threshold ::us/integer)
(s/def ::profile-complaint-max-age ::dt/duration)
(s/def ::profile-complaint-threshold ::us/integer)
(s/def ::public-uri ::us/string)
(s/def ::redis-uri ::us/string)
(s/def ::registration-domain-whitelist ::us/string)
(s/def ::registration-enabled ::us/boolean)
(s/def ::rlimits-image ::us/integer)
(s/def ::rlimits-password ::us/integer)
(s/def ::smtp-default-from ::us/string)
(s/def ::smtp-default-reply-to ::us/string)
(s/def ::smtp-enabled ::us/boolean)
(s/def ::smtp-host ::us/string)
(s/def ::smtp-password (s/nilable ::us/string))
(s/def ::smtp-port ::us/integer)
(s/def ::smtp-ssl ::us/boolean)
(s/def ::smtp-tls ::us/boolean)
(s/def ::smtp-username (s/nilable ::us/string))
(s/def ::srepl-host ::us/string)
(s/def ::srepl-port ::us/integer)
(s/def ::storage-backend ::us/keyword)
(s/def ::storage-fs-directory ::us/string)
(s/def ::storage-s3-bucket ::us/string)
(s/def ::storage-s3-region ::us/keyword)
(s/def ::telemetry-enabled ::us/boolean)
(s/def ::telemetry-server-enabled ::us/boolean)
(s/def ::telemetry-server-port ::us/integer)
(s/def ::telemetry-uri ::us/string)
(s/def ::telemetry-with-taiga ::us/boolean)
(s/def ::tenant ::us/string)

(s/def ::config
  (s/keys :opt-un [::secret-key
                   ::allow-demo-users
                   ::asserts-enabled
                   ::database-password
                   ::database-uri
                   ::database-username
                   ::default-blob-version
                   ::error-report-webhook
                   ::feedback-destination
                   ::feedback-enabled
                   ::feedback-token
                   ::github-client-id
                   ::github-client-secret
                   ::gitlab-base-uri
                   ::gitlab-client-id
                   ::gitlab-client-secret
                   ::google-client-id
                   ::google-client-secret
                   ::oidc-client-id
                   ::oidc-client-secret
                   ::oidc-base-uri
                   ::oidc-token-uri
                   ::oidc-auth-uri
                   ::oidc-user-uri
                   ::oidc-scopes
                   ::oidc-roles-attr
                   ::oidc-roles
                   ::host
                   ::http-server-port
                   ::http-session-idle-max-age
                   ::http-session-updater-batch-max-age
                   ::http-session-updater-batch-max-size
                   ::initial-project-skey
                   ::ldap-attrs-email
                   ::ldap-attrs-fullname
                   ::ldap-attrs-photo
                   ::ldap-attrs-username
                   ::ldap-base-dn
                   ::ldap-bind-dn
                   ::ldap-bind-password
                   ::ldap-host
                   ::ldap-port
                   ::ldap-ssl
                   ::ldap-starttls
                   ::ldap-user-query
                   ::local-assets-uri
                   ::loggers-loki-uri
                   ::loggers-zmq-uri
                   ::profile-bounce-max-age
                   ::profile-bounce-threshold
                   ::profile-complaint-max-age
                   ::profile-complaint-threshold
                   ::public-uri
                   ::redis-uri
                   ::registration-domain-whitelist
                   ::registration-enabled
                   ::rlimits-image
                   ::rlimits-password
                   ::smtp-default-from
                   ::smtp-default-reply-to
                   ::smtp-enabled
                   ::smtp-host
                   ::smtp-password
                   ::smtp-port
                   ::smtp-ssl
                   ::smtp-tls
                   ::smtp-username
                   ::srepl-host
                   ::srepl-port
                   ::storage-backend
                   ::storage-fs-directory
                   ::storage-s3-bucket
                   ::storage-s3-region
                   ::telemetry-enabled
                   ::telemetry-server-enabled
                   ::telemetry-server-port
                   ::telemetry-uri
                   ::telemetry-referer
                   ::telemetry-with-taiga
                   ::tenant]))

(defn read-env
  [prefix]
  (let [prefix (str prefix "-")
        len    (count prefix)]
    (reduce-kv
     (fn [acc k v]
       (cond-> acc
         (str/starts-with? (name k) prefix)
         (assoc (keyword (subs (name k) len)) v)))
     {}
     env)))

(defn- read-config
  []
  (->> (read-env "penpot")
       (merge defaults)
       (us/conform ::config)))

(def version (v/parse (or (some-> (io/resource "version.txt")
                                  (slurp)
                                  (str/trim))
                          "%version%")))
(def config (atom (read-config)))

(def deletion-delay
  (dt/duration {:days 7}))

(defn get
  "A configuration getter. Helps code be more testable."
  ([key]
   (c/get @config key))
  ([key default]
   (c/get @config key default)))

;; Set value for all new threads bindings.
(alter-var-root #'*assert* (constantly (get :asserts-enabled)))
