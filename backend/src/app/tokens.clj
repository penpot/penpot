;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.tokens
  "Tokens generation service."
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [integrant.core :as ig]
   [app.config :as cfg]
   [app.util.time :as dt]
   [app.util.transit :as t]
   [buddy.core.kdf :as bk]
   [buddy.sign.jwe :as jwe]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]))

(defn- derive-tokens-secret
  [key]
  (when (= key "default")
    (log/warn "Using default APP_SECRET_KEY, the system will generate insecure tokens."))
  (let [engine (bk/engine {:key key
                           :salt "tokens"
                           :alg :hkdf
                           :digest :blake2b-512})]
    (bk/get-bytes engine 32)))

(defn- generate
  [cfg claims]
  (let [payload (t/encode claims)]
    (jwe/encrypt payload (::secret cfg) {:alg :a256kw :enc :a256gcm})))

(defn- verify
  [cfg {:keys [token] :as params}]
  (let [payload (jwe/decrypt token (::secret cfg) {:alg :a256kw :enc :a256gcm})
        claims  (t/decode payload)]
    (when (and (dt/instant? (:exp claims))
               (dt/is-before? (:exp claims) (dt/now)))
      (ex/raise :type :validation
                :code :invalid-token
                :reason :token-expired
                :params params
                :claims claims))
    (when (and (contains? params :iss)
               (not= (:iss claims)
                     (:iss params)))
      (ex/raise :type :validation
                :code :invalid-token
                :reason :invalid-issuer
                :claims claims
                :params params))
    claims))

(s/def ::secret-key ::us/not-empty-string)

(defmethod ig/pre-init-spec ::tokens [_]
  (s/keys :req-un [::secret-key]))

(defmethod ig/init-key ::tokens
  [_ cfg]
  (let [secret (derive-tokens-secret (:secret-key cfg))
        cfg    (assoc cfg ::secret secret)]
    (fn [action params]
      (case action
        :verify (verify cfg params)
        :generate (generate cfg params)))))
