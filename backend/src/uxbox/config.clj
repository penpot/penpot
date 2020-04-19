;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2017-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.config
  "A configuration management."
  (:require
   [clojure.tools.logging :as log]
   [clojure.spec.alpha :as s]
   [uxbox.common.spec :as us]
   [cuerdas.core :as str]
   [environ.core :refer [env]]
   [mount.core :refer [defstate]]
   [uxbox.common.exceptions :as ex]
   [uxbox.util.time :as tm]))

(def defaults
  {:http-server-port 6060
   :http-server-cors "http://localhost:3449"
   :database-uri "postgresql://127.0.0.1/uxbox"
   :database-username "uxbox"
   :database-password "uxbox"

   :redis-uri "redis://redis/0"
   :media-directory "resources/public/media"
   :assets-directory "resources/public/static"
   :media-uri "http://localhost:6060/media/"
   :assets-uri "http://localhost:6060/static/"
   :email-reply-to "no-reply@nodomain.com"
   :email-from "no-reply@nodomain.com"
   :smtp-enabled false
   :allow-demo-users true
   :registration-enabled true
   :registration-domain-whitelist ""
   :debug-humanize-transit true
   })

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
(s/def ::email-reply-to ::us/email)
(s/def ::email-from ::us/email)
(s/def ::smtp-host ::us/string)
(s/def ::smtp-port ::us/integer)
(s/def ::smtp-user (s/nilable ::us/string))
(s/def ::smtp-password (s/nilable ::us/string))
(s/def ::smtp-tls ::us/boolean)
(s/def ::smtp-ssl ::us/boolean)
(s/def ::smtp-enabled ::us/boolean)
(s/def ::allow-demo-users ::us/boolean)
(s/def ::registration-enabled ::us/boolean)
(s/def ::registration-domain-whitelist ::us/string)
(s/def ::debug-humanize-transit ::us/boolean)

(s/def ::config
  (s/keys :opt-un [::http-server-cors
                   ::http-server-debug
                   ::http-server-port
                   ::database-username
                   ::database-password
                   ::database-uri
                   ::assets-directory
                   ::assets-uri
                   ::media-directory
                   ::media-uri
                   ::email-reply-to
                   ::email-from
                   ::smtp-host
                   ::smtp-port
                   ::smtp-user
                   ::smtp-password
                   ::smtp-tls
                   ::smtp-ssl
                   ::smtp-enabled
                   ::debug-humanize-transit
                   ::allow-demo-users
                   ::registration-enabled]))

(defn env->config
  [env]
  (reduce-kv (fn [acc k v]
               (cond-> acc
                 (str/starts-with? (name k) "uxbox-")
                 (assoc (keyword (subs (name k) 6)) v)))
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
         :database-uri "postgresql://postgres/uxbox_test"
         :media-directory "/tmp/uxbox/media"
         :assets-directory "/tmp/uxbox/static"
         :migrations-verbose false))

(defstate config
  :start (read-config env))

(def default-deletion-delay
  (tm/duration {:hours 48}))
