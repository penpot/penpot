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
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]
   [environ.core :refer [env]]
   [mount.core :refer [defstate]]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.util.time :as dt]))

(def defaults
  {:http-server-port 6060
   :http-server-cors "http://localhost:3449"
   :database-uri "postgresql://127.0.0.1/uxbox"
   :database-username "uxbox"
   :database-password "uxbox"
   :secret-key "default"

   :media-directory "resources/public/media"
   :assets-directory "resources/public/static"

   :public-uri "http://localhost:3449/"
   :redis-uri "redis://redis/0"
   :media-uri "http://localhost:3449/media/"
   :assets-uri "http://localhost:3449/static/"

   :image-process-max-threads 2

   :sendmail-backend "console"
   :sendmail-reply-to "no-reply@example.com"
   :sendmail-from "no-reply@example.com"

   :allow-demo-users true
   :registration-enabled true
   :registration-domain-whitelist ""
   :debug-humanize-transit true

   ;; This is the time should transcurr after the last page
   ;; modification in order to make the file ellegible for
   ;; trimming. The value only supports s(econds) m(inutes) and
   ;; h(ours) as time unit.
   :file-trimming-max-age "72h"

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
(s/def ::assets-uri ::us/string)
(s/def ::assets-directory ::us/string)
(s/def ::media-uri ::us/string)
(s/def ::media-directory ::us/string)
(s/def ::secret-key ::us/string)
(s/def ::sendmail-backend ::us/string)
(s/def ::sendmail-backend-apikey ::us/string)
(s/def ::sendmail-reply-to ::us/email)
(s/def ::sendmail-from ::us/email)
(s/def ::smtp-host ::us/string)
(s/def ::smtp-port ::us/integer)
(s/def ::smtp-user (s/nilable ::us/string))
(s/def ::smtp-password (s/nilable ::us/string))
(s/def ::smtp-tls ::us/boolean)
(s/def ::smtp-ssl ::us/boolean)
(s/def ::allow-demo-users ::us/boolean)
(s/def ::registration-enabled ::us/boolean)
(s/def ::registration-domain-whitelist ::us/string)
(s/def ::debug-humanize-transit ::us/boolean)
(s/def ::public-uri ::us/string)
(s/def ::backend-uri ::us/string)
(s/def ::image-process-max-threads ::us/integer)

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
(s/def ::file-trimming-threshold ::dt/duration)

(s/def ::config
  (s/keys :opt-un [::http-server-cors
                   ::http-server-debug
                   ::http-server-port
                   ::google-client-id
                   ::google-client-secret
                   ::gitlab-client-id
                   ::gitlab-client-secret
                   ::gitlab-base-uri
                   ::public-uri
                   ::database-username
                   ::database-password
                   ::database-uri
                   ::assets-directory
                   ::assets-uri
                   ::media-directory
                   ::media-uri
                   ::secret-key
                   ::sendmail-reply-to
                   ::sendmail-from
                   ::sendmail-backend
                   ::sendmail-backend-apikey
                   ::smtp-host
                   ::smtp-port
                   ::smtp-user
                   ::smtp-password
                   ::smtp-tls
                   ::smtp-ssl
                   ::file-trimming-max-age
                   ::debug-humanize-transit
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

(defn env->config
  [env]
  (reduce-kv
   (fn [acc k v]
     (cond-> acc
       (str/starts-with? (name k) "uxbox-")
       (assoc (keyword (subs (name k) 6)) v)

       (str/starts-with? (name k) "app-")
       (assoc (keyword (subs (name k) 4)) v)))
   {}
   env))

(defn read-config
  [env]
  (->> (env->config env)
       (merge defaults)
       (us/conform ::config)))

(defn read-test-config
  [env]
  (assoc (read-config env)
         :redis-uri "redis://redis/1"
         :database-uri "postgresql://postgres/uxbox_test"
         :media-directory "/tmp/app/media"
         :assets-directory "/tmp/app/static"
         :migrations-verbose false))

(defstate config
  :start (read-config env))

(def default-deletion-delay
  (dt/duration {:hours 48}))
