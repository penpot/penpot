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
  {:http-server-port (lookup-env env :uxbox-http-server-port 6060)
   :http-server-debug (lookup-env env :uxbox-http-server-debug true)
   :http-server-cors (lookup-env env :uxbox-http-server-cors "http://localhost:3449")

   :database-username (lookup-env env :uxbox-database-username nil)
   :database-password (lookup-env env :uxbox-database-password nil)
   :database-uri (lookup-env env :uxbox-database-uri "postgresql://127.0.0.1/uxbox")
   :media-directory (lookup-env env :uxbox-media-directory "resources/public/media")
   :media-uri (lookup-env env :uxbox-media-uri "http://localhost:6060/media/")
   :assets-directory (lookup-env env :uxbox-assets-directory "resources/public/static")
   :assets-uri (lookup-env env :uxbox-assets-uri "http://localhost:6060/static/")

   :google-api-key (lookup-env env :uxbox-google-api-key nil)

   :email-reply-to (lookup-env env :uxbox-email-reply-to "no-reply@nodomain.com")
   :email-from (lookup-env env :uxbox-email-from "no-reply@nodomain.com")

   :smtp-host (lookup-env env :uxbox-smtp-host "smtp")
   :smtp-port (lookup-env env :uxbox-smtp-port 25)
   :smtp-user (lookup-env env :uxbox-smtp-user nil)
   :smtp-password (lookup-env env :uxbox-smtp-password nil)
   :smtp-tls (lookup-env env :uxbox-smtp-tls false)
   :smtp-ssl (lookup-env env :uxbox-smtp-ssl false)
   :smtp-enabled (lookup-env env :uxbox-smtp-enabled false)

   :allow-demo-users (lookup-env env :uxbox-allow-demo-users true)
   :registration-enabled (lookup-env env :uxbox-registration-enabled true)})

(defn read-test-config
  []
  (assoc (read-config)
         :database-uri "postgresql://postgres/uxbox_test"
         :media-directory "/tmp/uxbox/media"
         :assets-directory "/tmp/uxbox/static"
         :migrations-verbose false))

(defstate config
  :start (read-config))

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

