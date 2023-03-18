;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.config
  "A configuration management."
  (:refer-clojure :exclude [get])
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.flags :as flags]
   [app.common.spec :as us]
   [app.common.version :as v]
   [app.util.time :as dt]
   [clojure.core :as c]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [environ.core :refer [env]]
   [integrant.core :as ig]))

(prefer-method print-method
               clojure.lang.IRecord
               clojure.lang.IDeref)

(prefer-method print-method
               clojure.lang.IPersistentMap
               clojure.lang.IDeref)

(prefer-method pprint/simple-dispatch
               clojure.lang.IPersistentMap
               clojure.lang.IDeref)

(defmethod ig/init-key :default
  [_ data]
  (d/without-nils data))

(defmethod ig/prep-key :default
  [_ data]
  (if (map? data)
    (d/without-nils data)
    data))

(def defaults
  {:database-uri "postgresql://postgres/penpot"
   :database-username "penpot"
   :database-password "penpot"

   :default-blob-version 5

   :rpc-rlimit-config (fs/path "resources/rlimit.edn")
   :rpc-climit-config (fs/path "resources/climit.edn")

   :file-change-snapshot-every 5
   :file-change-snapshot-timeout "3h"

   :public-uri "http://localhost:3449"
   :host "localhost"
   :tenant "default"

   :redis-uri "redis://redis/0"

   :assets-storage-backend :assets-fs
   :storage-assets-fs-directory "assets"

   :assets-path "/internal/assets/"
   :smtp-default-reply-to "Penpot <no-reply@example.com>"
   :smtp-default-from "Penpot <no-reply@example.com>"

   :profile-complaint-max-age (dt/duration {:days 7})
   :profile-complaint-threshold 2

   :profile-bounce-max-age (dt/duration {:days 7})
   :profile-bounce-threshold 10

   :telemetry-uri "https://telemetry.penpot.app/"

   :ldap-user-query "(|(uid=:username)(mail=:username))"
   :ldap-attrs-username "uid"
   :ldap-attrs-email "mail"
   :ldap-attrs-fullname "cn"

   ;; a server prop key where initial project is stored.
   :initial-project-skey "initial-project"})

(s/def ::default-rpc-rlimit ::us/vector-of-strings)
(s/def ::rpc-rlimit-config ::fs/path)
(s/def ::rpc-climit-config ::fs/path)

(s/def ::media-max-file-size ::us/integer)

(s/def ::flags ::us/vector-of-keywords)
(s/def ::telemetry-enabled ::us/boolean)

(s/def ::audit-log-archive-uri ::us/string)
(s/def ::audit-log-http-handler-concurrency ::us/integer)

(s/def ::admins ::us/set-of-valid-emails)
(s/def ::file-change-snapshot-every ::us/integer)
(s/def ::file-change-snapshot-timeout ::dt/duration)

(s/def ::default-executor-parallelism ::us/integer)
(s/def ::scheduled-executor-parallelism ::us/integer)

(s/def ::worker-default-parallelism ::us/integer)
(s/def ::worker-webhook-parallelism ::us/integer)

(s/def ::authenticated-cookie-domain ::us/string)
(s/def ::authenticated-cookie-name ::us/string)
(s/def ::auth-token-cookie-name ::us/string)
(s/def ::auth-token-cookie-max-age ::dt/duration)

(s/def ::secret-key ::us/string)
(s/def ::allow-demo-users ::us/boolean)
(s/def ::assets-path ::us/string)
(s/def ::database-password (s/nilable ::us/string))
(s/def ::database-uri ::us/string)
(s/def ::database-username (s/nilable ::us/string))
(s/def ::database-readonly ::us/boolean)
(s/def ::database-min-pool-size ::us/integer)
(s/def ::database-max-pool-size ::us/integer)

(s/def ::quotes-teams-per-profile ::us/integer)
(s/def ::quotes-access-tokens-per-profile ::us/integer)
(s/def ::quotes-projects-per-team ::us/integer)
(s/def ::quotes-invitations-per-team ::us/integer)
(s/def ::quotes-profiles-per-team ::us/integer)
(s/def ::quotes-files-per-project ::us/integer)
(s/def ::quotes-files-per-team ::us/integer)
(s/def ::quotes-font-variants-per-team ::us/integer)
(s/def ::quotes-comment-threads-per-file ::us/integer)
(s/def ::quotes-comments-per-file ::us/integer)

(s/def ::default-blob-version ::us/integer)
(s/def ::error-report-webhook ::us/string)
(s/def ::user-feedback-destination ::us/string)
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
(s/def ::oidc-scopes ::us/set-of-strings)
(s/def ::oidc-roles ::us/set-of-strings)
(s/def ::oidc-roles-attr ::us/keyword)
(s/def ::oidc-email-attr ::us/keyword)
(s/def ::oidc-name-attr ::us/keyword)
(s/def ::host ::us/string)
(s/def ::http-server-port ::us/integer)
(s/def ::http-server-host ::us/string)
(s/def ::http-server-max-body-size ::us/integer)
(s/def ::http-server-max-multipart-body-size ::us/integer)
(s/def ::http-server-io-threads ::us/integer)
(s/def ::http-server-worker-threads ::us/integer)
(s/def ::ldap-attrs-email ::us/string)
(s/def ::ldap-attrs-fullname ::us/string)
(s/def ::ldap-attrs-username ::us/string)
(s/def ::ldap-base-dn ::us/string)
(s/def ::ldap-bind-dn ::us/string)
(s/def ::ldap-bind-password ::us/string)
(s/def ::ldap-host ::us/string)
(s/def ::ldap-port ::us/integer)
(s/def ::ldap-ssl ::us/boolean)
(s/def ::ldap-starttls ::us/boolean)
(s/def ::ldap-user-query ::us/string)
(s/def ::media-directory ::us/string)
(s/def ::media-uri ::us/string)
(s/def ::profile-bounce-max-age ::dt/duration)
(s/def ::profile-bounce-threshold ::us/integer)
(s/def ::profile-complaint-max-age ::dt/duration)
(s/def ::profile-complaint-threshold ::us/integer)
(s/def ::public-uri ::us/string)
(s/def ::redis-uri ::us/string)
(s/def ::registration-domain-whitelist ::us/set-of-strings)

(s/def ::smtp-default-from ::us/string)
(s/def ::smtp-default-reply-to ::us/string)
(s/def ::smtp-host ::us/string)
(s/def ::smtp-password (s/nilable ::us/string))
(s/def ::smtp-port ::us/integer)
(s/def ::smtp-ssl ::us/boolean)
(s/def ::smtp-tls ::us/boolean)
(s/def ::smtp-username (s/nilable ::us/string))
(s/def ::urepl-host ::us/string)
(s/def ::urepl-port ::us/integer)
(s/def ::prepl-host ::us/string)
(s/def ::prepl-port ::us/integer)
(s/def ::assets-storage-backend ::us/keyword)
(s/def ::storage-assets-fs-directory ::us/string)
(s/def ::storage-assets-s3-bucket ::us/string)
(s/def ::storage-assets-s3-region ::us/keyword)
(s/def ::storage-assets-s3-endpoint ::us/string)
(s/def ::telemetry-uri ::us/string)
(s/def ::telemetry-with-taiga ::us/boolean)
(s/def ::tenant ::us/string)

(s/def ::config
  (s/keys :opt-un [::secret-key
                   ::flags
                   ::admins
                   ::allow-demo-users
                   ::audit-log-archive-uri
                   ::audit-log-http-handler-concurrency
                   ::auth-token-cookie-name
                   ::auth-token-cookie-max-age
                   ::authenticated-cookie-name
                   ::authenticated-cookie-domain
                   ::database-password
                   ::database-uri
                   ::database-username
                   ::database-readonly
                   ::database-min-pool-size
                   ::database-max-pool-size
                   ::default-blob-version
                   ::default-rpc-rlimit
                   ::error-report-webhook
                   ::default-executor-parallelism
                   ::scheduled-executor-parallelism
                   ::worker-default-parallelism
                   ::worker-webhook-parallelism
                   ::file-change-snapshot-every
                   ::file-change-snapshot-timeout
                   ::user-feedback-destination
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
                   ::oidc-email-attr
                   ::oidc-name-attr
                   ::oidc-roles
                   ::host
                   ::http-server-host
                   ::http-server-port
                   ::http-server-max-body-size
                   ::http-server-max-multipart-body-size
                   ::http-server-io-threads
                   ::http-server-worker-threads
                   ::ldap-attrs-email
                   ::ldap-attrs-fullname
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
                   ::media-max-file-size
                   ::profile-bounce-max-age
                   ::profile-bounce-threshold
                   ::profile-complaint-max-age
                   ::profile-complaint-threshold
                   ::public-uri

                   ::quotes-teams-per-profile
                   ::quotes-access-tokens-per-profile
                   ::quotes-projects-per-team
                   ::quotes-invitations-per-team
                   ::quotes-profiles-per-team
                   ::quotes-files-per-project
                   ::quotes-files-per-team
                   ::quotes-font-variants-per-team
                   ::quotes-comment-threads-per-file
                   ::quotes-comments-per-file

                   ::redis-uri
                   ::registration-domain-whitelist
                   ::rpc-rlimit-config

                   ::semaphore-process-font
                   ::semaphore-process-image
                   ::semaphore-update-file
                   ::semaphore-auth

                   ::smtp-default-from
                   ::smtp-default-reply-to
                   ::smtp-host
                   ::smtp-password
                   ::smtp-port
                   ::smtp-ssl
                   ::smtp-tls
                   ::smtp-username

                   ::urepl-host
                   ::urepl-port
                   ::prepl-host
                   ::prepl-port

                   ::assets-storage-backend
                   ::storage-assets-fs-directory
                   ::storage-assets-s3-bucket
                   ::storage-assets-s3-region
                   ::storage-assets-s3-endpoint
                   ::telemetry-enabled
                   ::telemetry-uri
                   ::telemetry-referer
                   ::telemetry-with-taiga
                   ::tenant]))

(def default-flags
  [:enable-backend-api-doc
   :enable-backend-openapi-doc
   :enable-backend-worker
   :enable-secure-session-cookies
   :enable-email-verification])

(defn- parse-flags
  [config]
  (flags/parse flags/default
               default-flags
               (:flags config)))

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
  (try
    (->> (read-env "penpot")
         (merge defaults)
         (us/conform ::config))
    (catch Throwable e
      (when (ex/error? e)
        (println ";;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;")
        (println "Error on validating configuration:")
        (println (some-> e ex-data ex/explain))
        (println (ex/explain (ex-data e)))
        (println ";;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;"))
      (throw e))))

(def version
  (v/parse (or (some-> (io/resource "version.txt")
                       (slurp)
                       (str/trim))
               "%version%")))

(defonce ^:dynamic config (read-config))
(defonce ^:dynamic flags (parse-flags config))

(def deletion-delay
  (dt/duration {:days 7}))

(defn get
  "A configuration getter. Helps code be more testable."
  ([key]
   (c/get config key))
  ([key default]
   (c/get config key default)))

;; Set value for all new threads bindings.
(alter-var-root #'*assert* (constantly (contains? flags :backend-asserts)))
