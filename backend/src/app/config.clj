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
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uri :as u]
   [app.common.version :as v]
   [app.util.overrides]
   [clojure.core :as c]
   [clojure.java.io :as io]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [environ.core :refer [env]]
   [integrant.core :as ig]))

(defmethod ig/init-key :default
  [_ data]
  (d/without-nils data))

(defmethod ig/expand-key :default
  [k v]
  {k (if (map? v)
       (d/without-nils v)
       v)})

(def default
  {:database-uri "postgresql://postgres/penpot"
   :database-username "penpot"
   :database-password "penpot"

   :default-blob-version 4

   :rpc-rlimit-config "resources/rlimit.edn"
   :rpc-climit-config "resources/climit.edn"

   :auto-file-snapshot-every 5
   :auto-file-snapshot-timeout "3h"

   :public-uri "http://localhost:3449"
   :host "localhost"
   :tenant "default"

   :redis-uri "redis://redis/0"

   :objects-storage-backend "fs"
   :objects-storage-fs-directory "assets"

   :assets-path "/internal/assets/"
   :smtp-default-reply-to "Penpot <no-reply@example.com>"
   :smtp-default-from "Penpot <no-reply@example.com>"

   :profile-complaint-max-age (ct/duration {:days 7})
   :profile-complaint-threshold 2

   :profile-bounce-max-age (ct/duration {:days 7})
   :profile-bounce-threshold 10

   :telemetry-uri "https://telemetry.penpot.app/"

   :media-max-file-size (* 1024 1024 30) ; 30MiB

   :ldap-user-query "(|(uid=:username)(mail=:username))"
   :ldap-attrs-username "uid"
   :ldap-attrs-email "mail"
   :ldap-attrs-fullname "cn"

   ;; a server prop key where initial project is stored.
   :initial-project-skey "initial-project"

   ;; time to avoid email sending after profile modification
   :email-verify-threshold "15m"})

(def schema:config
  (do #_sm/optional-keys
   [:map {:title "config"}
    [:flags {:optional true} [::sm/set :string]]
    [:admins {:optional true} [::sm/set ::sm/email]]
    [:secret-key {:optional true} :string]

    [:tenant {:optional false} :string]
    [:public-uri {:optional false} :string]
    [:host {:optional false} :string]

    [:http-server-port {:optional true} ::sm/int]
    [:http-server-host {:optional true} :string]
    [:http-server-max-body-size {:optional true} ::sm/int]
    [:http-server-max-multipart-body-size {:optional true} ::sm/int]
    [:http-server-io-threads {:optional true} ::sm/int]
    [:http-server-worker-threads {:optional true} ::sm/int]

    [:telemetry-uri {:optional true} :string]
    [:telemetry-with-taiga {:optional true} ::sm/boolean] ;; DELETE

    [:auto-file-snapshot-every {:optional true} ::sm/int]
    [:auto-file-snapshot-timeout {:optional true} ::ct/duration]

    [:media-max-file-size {:optional true} ::sm/int]
    [:deletion-delay {:optional true} ::ct/duration] ;; REVIEW
    [:telemetry-enabled {:optional true} ::sm/boolean]
    [:default-blob-version {:optional true} ::sm/int]
    [:allow-demo-users {:optional true} ::sm/boolean]
    [:error-report-webhook {:optional true} :string]
    [:user-feedback-destination {:optional true} :string]

    [:default-rpc-rlimit {:optional true} [::sm/vec :string]]
    [:rpc-rlimit-config {:optional true} ::fs/path]
    [:rpc-climit-config {:optional true} ::fs/path]

    [:audit-log-archive-uri {:optional true} :string]
    [:audit-log-http-handler-concurrency {:optional true} ::sm/int]

    [:default-executor-parallelism {:optional true} ::sm/int] ;; REVIEW
    [:scheduled-executor-parallelism {:optional true} ::sm/int] ;; REVIEW
    [:worker-default-parallelism {:optional true} ::sm/int]
    [:worker-webhook-parallelism {:optional true} ::sm/int]

    [:database-password {:optional true} [:maybe :string]]
    [:database-uri {:optional true} ::sm/uri]
    [:database-username {:optional true} [:maybe :string]]
    [:database-readonly {:optional true} ::sm/boolean]
    [:database-min-pool-size {:optional true} ::sm/int]
    [:database-max-pool-size {:optional true} ::sm/int]

    [:quotes-teams-per-profile {:optional true} ::sm/int]
    [:quotes-access-tokens-per-profile {:optional true} ::sm/int]
    [:quotes-projects-per-team {:optional true} ::sm/int]
    [:quotes-invitations-per-team {:optional true} ::sm/int]
    [:quotes-profiles-per-team {:optional true} ::sm/int]
    [:quotes-files-per-project {:optional true} ::sm/int]
    [:quotes-files-per-team {:optional true} ::sm/int]
    [:quotes-font-variants-per-team {:optional true} ::sm/int]
    [:quotes-comment-threads-per-file {:optional true} ::sm/int]
    [:quotes-comments-per-file {:optional true} ::sm/int]
    [:quotes-snapshots-per-file {:optional true} ::sm/int]
    [:quotes-snapshots-per-team {:optional true} ::sm/int]
    [:quotes-team-access-requests-per-team {:optional true} ::sm/int]
    [:quotes-team-access-requests-per-requester {:optional true} ::sm/int]

    [:auth-data-cookie-domain {:optional true} :string]
    [:auth-token-cookie-name {:optional true} :string]
    [:auth-token-cookie-max-age {:optional true} ::ct/duration]

    [:registration-domain-whitelist {:optional true} [::sm/set :string]]
    [:email-verify-threshold {:optional true} ::ct/duration]

    [:github-client-id {:optional true} :string]
    [:github-client-secret {:optional true} :string]
    [:gitlab-base-uri {:optional true} :string]
    [:gitlab-client-id {:optional true} :string]
    [:gitlab-client-secret {:optional true} :string]
    [:google-client-id {:optional true} :string]
    [:google-client-secret {:optional true} :string]
    [:oidc-client-id {:optional true} :string]
    [:oidc-user-info-source {:optional true} :keyword]
    [:oidc-client-secret {:optional true} :string]
    [:oidc-base-uri {:optional true} :string]
    [:oidc-token-uri {:optional true} :string]
    [:oidc-auth-uri {:optional true} :string]
    [:oidc-user-uri {:optional true} :string]
    [:oidc-jwks-uri {:optional true} :string]
    [:oidc-scopes {:optional true} [::sm/set :string]]
    [:oidc-roles {:optional true} [::sm/set :string]]
    [:oidc-roles-attr {:optional true} :string]
    [:oidc-email-attr {:optional true} :string]
    [:oidc-name-attr {:optional true} :string]

    [:ldap-attrs-email {:optional true} :string]
    [:ldap-attrs-fullname {:optional true} :string]
    [:ldap-attrs-username {:optional true} :string]
    [:ldap-base-dn {:optional true} :string]
    [:ldap-bind-dn {:optional true} :string]
    [:ldap-bind-password {:optional true} :string]
    [:ldap-host {:optional true} :string]
    [:ldap-port {:optional true} ::sm/int]
    [:ldap-ssl {:optional true} ::sm/boolean]
    [:ldap-starttls {:optional true} ::sm/boolean]
    [:ldap-user-query {:optional true} :string]

    [:profile-bounce-max-age {:optional true} ::ct/duration]
    [:profile-bounce-threshold {:optional true} ::sm/int]
    [:profile-complaint-max-age {:optional true} ::ct/duration]
    [:profile-complaint-threshold {:optional true} ::sm/int]

    [:redis-uri {:optional true} ::sm/uri]

    [:email-domain-blacklist {:optional true} ::fs/path]
    [:email-domain-whitelist {:optional true} ::fs/path]

    [:smtp-default-from {:optional true} :string]
    [:smtp-default-reply-to {:optional true} :string]
    [:smtp-host {:optional true} :string]
    [:smtp-password {:optional true} [:maybe :string]]
    [:smtp-port {:optional true} ::sm/int]
    [:smtp-ssl {:optional true} ::sm/boolean]
    [:smtp-tls {:optional true} ::sm/boolean]
    [:smtp-username {:optional true} [:maybe :string]]

    [:urepl-host {:optional true} :string]
    [:urepl-port {:optional true} ::sm/int]
    [:prepl-host {:optional true} :string]
    [:prepl-port {:optional true} ::sm/int]

    [:media-directory {:optional true} :string] ;; REVIEW
    [:media-uri {:optional true} :string]
    [:assets-path {:optional true} :string]

    ;; Legacy, will be removed in 2.5
    [:assets-storage-backend {:optional true} :keyword]
    [:storage-assets-fs-directory {:optional true} :string]
    [:storage-assets-s3-bucket {:optional true} :string]
    [:storage-assets-s3-region {:optional true} :keyword]
    [:storage-assets-s3-endpoint {:optional true} ::sm/uri]
    [:storage-assets-s3-io-threads {:optional true} ::sm/int]

    [:objects-storage-backend {:optional true} :keyword]
    [:objects-storage-fs-directory {:optional true} :string]
    [:objects-storage-s3-bucket {:optional true} :string]
    [:objects-storage-s3-region {:optional true} :keyword]
    [:objects-storage-s3-endpoint {:optional true} ::sm/uri]
    [:objects-storage-s3-io-threads {:optional true} ::sm/int]]))

(defn- parse-flags
  [config]
  (let [public-uri  (c/get config :public-uri)
        public-uri  (some-> public-uri (u/uri))
        extra-flags (if (and public-uri
                             (= (:scheme public-uri) "http")
                             (not= (:host public-uri) "localhost"))
                      #{:disable-secure-session-cookies}
                      #{})]
    (flags/parse flags/default extra-flags (:flags config))))

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

(def decode-config
  (sm/decoder schema:config sm/string-transformer))

(def validate-config
  (sm/validator schema:config))

(def explain-config
  (sm/explainer schema:config))

(defn read-config
  "Reads the configuration from enviroment variables and decodes all
  known values."
  [& {:keys [prefix default] :or {prefix "penpot"}}]
  (->> (read-env prefix)
       (merge default)
       (decode-config)))

(def version
  (v/parse (or (some-> (io/resource "version.txt")
                       (slurp)
                       (str/trim))
               "%version%")))

(defonce ^:dynamic config (read-config :default default))
(defonce ^:dynamic flags (parse-flags config))

(defn validate!
  "Validate the currently loaded configuration data."
  [& {:keys [exit-on-error?] :or {exit-on-error? true}}]
  (if (validate-config config)
    true
    (let [explain (explain-config config)]
      (println "Error on validating configuration:")
      (sm/pretty-explain explain
                         :variant ::sm/schemaless-explain
                         :message "Configuration Validation Error")
      (flush)
      (if exit-on-error?
        (System/exit -1)
        (ex/raise :type :validation
                  :code :config-validaton
                  ::sm/explain explain)))))

(defn get-deletion-delay
  []
  (or (c/get config :deletion-delay)
      (ct/duration {:days 7})))

(defn get
  "A configuration getter. Helps code be more testable."
  ([key]
   (c/get config key))
  ([key default]
   (c/get config key default)))

;; Set value for all new threads bindings.
(alter-var-root #'*assert* (constantly (contains? flags :backend-asserts)))
