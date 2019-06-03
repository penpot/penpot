;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.config
  "A configuration management."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.edn :as edn]
            [cuerdas.core :as str]
            [buddy.core.hash :as hash]
            [environ.core :refer [env]]
            [mount.core :refer [defstate]]
            [uxbox.util.exceptions :as ex]
            [uxbox.util.data :refer [deep-merge]]))

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
  {:http-server-port (lookup-env env :uxbox-http-server-port 6060)
   :http-server-debug (lookup-env env :uxbox-http-server-debug true)
   :database-username (lookup-env env :uxbox-database-username nil)
   :database-password (lookup-env env :uxbox-database-password nil)
   :database-name (lookup-env env :uxbox-database-name "uxbox")
   :database-server (lookup-env env :uxbox-database-server "localhost")
   :database-port (lookup-env env :uxbox-database-port 5432)
   :media-directory (lookup-env env :uxbox-media-directory "resources/public/media")
   :media-uri (lookup-env env :uxbox-media-uri "http://localhost:6060/media/")
   :assets-directory (lookup-env env :uxbox-assets-directory "resources/public/static")
   :assets-uri (lookup-env env :uxbox-assets-uri "http://localhost:6060/static/")

   :email-reply-to (lookup-env env :uxbox-email-reply-to "no-reply@uxbox.io")
   :email-from (lookup-env env :uxbox-email-from "no-reply@uxbox.io")

   :smtp-host (lookup-env env :uxbox-smtp-host "localhost")
   :smtp-port (lookup-env env :uxbox-smtp-port 25)
   :smtp-user (lookup-env env :uxbox-smtp-user nil)
   :smtp-password (lookup-env env :uxbox-smtp-password nil)
   :smtp-tls (lookup-env env :uxbox-smtp-tls false)
   :smtp-ssl (lookup-env env :uxbox-smtp-ssl false)
   :smtp-enabled (lookup-env env :uxbox-smtp-enabled false)

   :registration-enabled (lookup-env env :uxbox-registration-enabled true)

   :secret (lookup-env env :uxbox-secret "5qjiAndGY3")})

(defn read-test-config
  []
  (assoc (read-config)
         :database-name "test"
         :media-directory "/tmp/uxbox/media"
         :assets-directory "/tmp/uxbox/static"
         :migrations-verbose false))

(defstate config
  :start (read-config))

;; --- Secret Loading & Parsing

(defn- initialize-secret
  [config]
  (let [secret (:secret config)]
    (when-not secret
      (ex/raise :code ::missing-secret-key
                :message "Missing `:secret` key in config."))
    (hash/blake2b-256 secret)))

(defstate secret
  :start (initialize-secret config))

