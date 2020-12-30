;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.config
  "A configuration management."
  (:require
   [app.common.spec :as us]
   [app.common.version :as v]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [environ.core :refer [env]]))

(def defaults
  {:http-server-port 6060
   :http-server-cors "http://localhost:3449"
   :database-uri "postgresql://127.0.0.1/penpot"
   :database-username "penpot"
   :database-password "penpot"
   :secret-key "default"
   :enabled-asserts true

   :public-uri "http://localhost:3449/"
   :redis-uri "redis://localhost/0"

   :storage-fs-directory "resources/public/media"
   :storage-fs-uri "http://localhost:3449/media/"
   :storage-s3-region :eu-central-1

   :image-process-max-threads 2

   :smtp-enabled false
   :smtp-default-reply-to "no-reply@example.com"
   :smtp-default-from "no-reply@example.com"

   :host "devenv"

   :allow-demo-users true
   :registration-enabled true
   :registration-domain-whitelist ""

   :telemetry-enabled true
   :telemetry-uri "http://localhost:6063/"

   :debug true

   ;; This is the time should transcurr after the last page
   ;; modification in order to make the file ellegible for
   ;; trimming. The value only supports s(econds) m(inutes) and
   ;; h(ours) as time unit.
   :file-trimming-threshold "72h"

   ;; LDAP auth disabled by default. Set ldap-auth-host to enable
   ;:ldap-auth-host "ldap.mysupercompany.com"
   ;:ldap-auth-port 389
   ;:ldap-bind-dn "cn=admin,dc=ldap,dc=mysupercompany,dc=com"
   ;:ldap-bind-password "verysecure"
   ;:ldap-auth-ssl false
   ;:ldap-auth-starttls false
   ;:ldap-auth-base-dn "ou=People,dc=ldap,dc=mysupercompany,dc=com"

   :ldap-auth-user-query "(|(uid=$username)(mail=$username))"
   :ldap-auth-username-attribute "uid"
   :ldap-auth-email-attribute "mail"
   :ldap-auth-fullname-attribute "displayName"
   :ldap-auth-avatar-attribute "jpegPhoto"})

(s/def ::http-server-port ::us/integer)
(s/def ::http-server-debug ::us/boolean)
(s/def ::http-server-cors ::us/string)
(s/def ::database-username (s/nilable ::us/string))
(s/def ::database-password (s/nilable ::us/string))
(s/def ::database-uri ::us/string)
(s/def ::redis-uri ::us/string)

(s/def ::storage-fs-directory ::us/string)
(s/def ::storage-fs-uri ::us/string)
(s/def ::storage-s3-region ::us/keyword)
(s/def ::storage-s3-bucket ::us/string)

(s/def ::media-uri ::us/string)
(s/def ::media-directory ::us/string)
(s/def ::secret-key ::us/string)
(s/def ::enable-asserts ::us/boolean)

(s/def ::host ::us/string)
(s/def ::error-report-webhook ::us/string)
(s/def ::smtp-enabled ::us/boolean)
(s/def ::smtp-default-reply-to ::us/email)
(s/def ::smtp-default-from ::us/email)
(s/def ::smtp-host ::us/string)
(s/def ::smtp-port ::us/integer)
(s/def ::smtp-username (s/nilable ::us/string))
(s/def ::smtp-password (s/nilable ::us/string))
(s/def ::smtp-tls ::us/boolean)
(s/def ::smtp-ssl ::us/boolean)
(s/def ::allow-demo-users ::us/boolean)
(s/def ::registration-enabled ::us/boolean)
(s/def ::registration-domain-whitelist ::us/string)
(s/def ::debug ::us/boolean)
(s/def ::public-uri ::us/string)
(s/def ::backend-uri ::us/string)

(s/def ::image-process-max-threads ::us/integer)
(s/def ::file-trimming-threshold ::dt/duration)

(s/def ::google-client-id ::us/string)
(s/def ::google-client-secret ::us/string)

(s/def ::gitlab-client-id ::us/string)
(s/def ::gitlab-client-secret ::us/string)
(s/def ::gitlab-base-uri ::us/string)

(s/def ::ldap-auth-host ::us/string)
(s/def ::ldap-auth-port ::us/integer)
(s/def ::ldap-bind-dn ::us/string)
(s/def ::ldap-bind-password ::us/string)
(s/def ::ldap-auth-ssl ::us/boolean)
(s/def ::ldap-auth-starttls ::us/boolean)
(s/def ::ldap-auth-base-dn ::us/string)
(s/def ::ldap-auth-user-query ::us/string)
(s/def ::ldap-auth-username-attribute ::us/string)
(s/def ::ldap-auth-email-attribute ::us/string)
(s/def ::ldap-auth-fullname-attribute ::us/string)
(s/def ::ldap-auth-avatar-attribute ::us/string)

(s/def ::telemetry-enabled ::us/boolean)
(s/def ::telemetry-uri ::us/string)
(s/def ::telemetry-server-enabled ::us/boolean)
(s/def ::telemetry-server-port ::us/integer)


(s/def ::config
  (s/keys :opt-un [::http-server-cors
                   ::http-server-debug
                   ::http-server-port
                   ::google-client-id
                   ::google-client-secret
                   ::gitlab-client-id
                   ::gitlab-client-secret
                   ::gitlab-base-uri
                   ::enable-asserts
                   ::redis-uri
                   ::public-uri
                   ::database-username
                   ::database-password
                   ::database-uri
                   ::storage-fs-directory
                   ::storage-fs-uri
                   ::storage-s3-bucket
                   ::storage-s3-region
                   ::error-report-webhook
                   ::secret-key
                   ::smtp-default-from
                   ::smtp-default-reply-to
                   ::smtp-enabled
                   ::smtp-host
                   ::smtp-port
                   ::smtp-username
                   ::smtp-password
                   ::smtp-tls
                   ::smtp-ssl
                   ::host
                   ::file-trimming-threshold
                   ::telemetry-enabled
                   ::telemetry-server-enabled
                   ::telemetry-uri
                   ::telemetry-server-port
                   ::debug
                   ::allow-demo-users
                   ::registration-enabled
                   ::registration-domain-whitelist
                   ::image-process-max-threads
                   ::ldap-auth-host
                   ::ldap-auth-port
                   ::ldap-bind-dn
                   ::ldap-bind-password
                   ::ldap-auth-ssl
                   ::ldap-auth-starttls
                   ::ldap-auth-base-dn
                   ::ldap-auth-user-query
                   ::ldap-auth-username-attribute
                   ::ldap-auth-email-attribute
                   ::ldap-auth-fullname-attribute
                   ::ldap-auth-avatar-attribute]))

(defn- env->config
  [env]
  (reduce-kv
   (fn [acc k v]
     (cond-> acc
       (str/starts-with? (name k) "penpot-")
       (assoc (keyword (subs (name k) 7)) v)

       (str/starts-with? (name k) "app-")
       (assoc (keyword (subs (name k) 4)) v)))
   {}
   env))

(defn- read-config
  [env]
  (->> (env->config env)
       (merge defaults)
       (us/conform ::config)))

(defn- read-test-config
  [env]
  (assoc (read-config env)
         :redis-uri "redis://redis/1"
         :database-uri "postgresql://postgres/penpot_test"
         :storage-fs-directory "/tmp/app/storage"
         :migrations-verbose false))

(def version (v/parse "%version%"))
(def config (read-config env))
(def test-config (read-test-config env))

(def default-deletion-delay
  (dt/duration {:hours 48}))

(prefer-method print-method
               clojure.lang.IRecord
               clojure.lang.IDeref)
