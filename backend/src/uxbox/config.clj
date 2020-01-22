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
   [uxbox.common.exceptions :as ex]))

;; --- Configuration Reading & Loading

(defn lookup-env
  [env key default]
  (let [value (get env key ::empty)]
    (if (= value ::empty)
      default
      (try
        (read-string value)
        (catch Exception e
          (log/warn (str/istr "can't parse `~{key}` env value"))
          default)))))



;; --- Configuration Loading & Parsing

(defn read-config
  []
  {:http-server-port (:uxbox-http-server-port env 6060)
   :http-server-debug (:uxbox-http-server-debug env true)
   :http-server-cors (:uxbox-http-server-cors env "http://localhost:3449")

   :database-username (:uxbox-database-username env nil)
   :database-password (:uxbox-database-password env nil)
   :database-uri (:uxbox-database-uri env "postgresql://127.0.0.1/uxbox")
   :media-directory (:uxbox-media-directory env "resources/public/media")
   :media-uri (:uxbox-media-uri env "http://localhost:6060/media/")
   :assets-directory (:uxbox-assets-directory env "resources/public/static")
   :assets-uri (:uxbox-assets-uri env "http://localhost:6060/static/")

   :google-api-key (:uxbox-google-api-key env nil)

   :email-reply-to (:uxbox-email-reply-to env "no-reply@nodomain.com")
   :email-from (:uxbox-email-from env "no-reply@nodomain.com")

   :smtp-host (:uxbox-smtp-host env "smtp")
   :smtp-port (:uxbox-smtp-port env 25)
   :smtp-user (:uxbox-smtp-user env nil)
   :smtp-password (:uxbox-smtp-password env nil)
   :smtp-tls (:uxbox-smtp-tls env false)
   :smtp-ssl (:uxbox-smtp-ssl env false)
   :smtp-enabled (:uxbox-smtp-enabled env false)

   :allow-demo-users (:uxbox-allow-demo-users env true)
   :registration-enabled (:uxbox-registration-enabled env true)})

(s/def ::http-server-port ::us/integer)
(s/def ::http-server-debug ::us/boolean)
(s/def ::http-server-cors ::us/string)
(s/def ::database-username (s/nilable ::us/string))
(s/def ::database-password (s/nilable ::us/string))
(s/def ::database-uri ::us/string)
(s/def ::assets-uri ::us/string)
(s/def ::assets-directory ::us/string)
(s/def ::media-uri ::us/string)
(s/def ::media-directory ::us/string)
(s/def ::email-reply-to ::us/email)
(s/def ::email-from ::us/email)
(s/def ::smtp-host ::us/string)
(s/def ::smtp-user (s/nilable ::us/string))
(s/def ::smtp-password (s/nilable ::us/string))
(s/def ::smtp-tls ::us/boolean)
(s/def ::smtp-ssl ::us/boolean)
(s/def ::smtp-enabled ::us/boolean)
(s/def ::allow-demo-users ::us/boolean)
(s/def ::registration-enabled ::us/boolean)

(s/def ::config
  (s/keys :req-un [::http-server-cors
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
                   ::smtp-user
                   ::smtp-password
                   ::smtp-tls
                   ::smtp-ssl
                   ::smtp-enabled
                   ::allow-demo-users
                   ::registration-enabled]))

(defn read-test-config
  []
  (assoc (read-config)
         :database-uri "postgresql://postgres/uxbox_test"
         :media-directory "/tmp/uxbox/media"
         :assets-directory "/tmp/uxbox/static"
         :migrations-verbose false))

(defstate config
  :start (us/conform ::config (read-config)))

;; --- Secret Loading & Parsing

;; (defn- initialize-secret
;;   [config]
;;   (let [secret (:secret config)]
;;     (when-not secret
;;       (ex/raise :code ::missing-secret-key
;;                 :message "Missing `:secret` key in config."))
;;     (hash/blake2b-256 secret)))
;;
;; (defstate secret
;;   :start (initialize-secret config))

