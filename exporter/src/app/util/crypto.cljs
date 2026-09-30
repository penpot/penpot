;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.util.crypto
  "Symmetric encryption for secrets that pass through redis.

  A queued job carries the session token its worker renders and uploads
  with. Redis is shared infrastructure, so the token is sealed with
  AES-256-GCM under a key derived from `PENPOT_SECRET_KEY`, which every
  exporter process already has."
  (:require
   ["node:buffer" :as buffer]
   ["node:crypto" :as crypto]
   [app.common.exceptions :as ex]
   [app.config :as cf]
   [cuerdas.core :as str]))

(def ^:private algorithm "aes-256-gcm")

(def ^:private secret
  (delay
    (.from buffer/Buffer (crypto/hkdfSync "sha256" (cf/get :secret-key) "" "exporter-queue" 32))))

(defn- b64
  [^js buf]
  (.toString buf "base64url"))

(defn- unb64
  [s]
  (.from buffer/Buffer s "base64url"))

(defn encrypt
  "Seals `plaintext` into a `iv.tag.body` string."
  [plaintext]
  (let [iv       (crypto/randomBytes 12)
        ^js cph  (crypto/createCipheriv algorithm @secret iv)
        body     (.concat buffer/Buffer #js [(.update cph plaintext "utf8") (.final cph)])]
    (str (b64 iv) "." (b64 (.getAuthTag cph)) "." (b64 body))))

(defn decrypt
  "Opens a string sealed by `encrypt`. Raises when it was altered or sealed
  under another key."
  [sealed]
  (let [[iv tag body :as parts] (str/split sealed ".")]
    (when-not (= 3 (count parts))
      (ex/raise :type :internal
                :code :invalid-sealed-value
                :hint "sealed value is malformed"))
    (let [^js dcp (crypto/createDecipheriv algorithm @secret (unb64 iv))]
      (.setAuthTag dcp (unb64 tag))
      (str (.update dcp (unb64 body) nil "utf8")
           (.final dcp "utf8")))))
