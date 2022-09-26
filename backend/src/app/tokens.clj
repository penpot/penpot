;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.tokens
  "Tokens generation API."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.transit :as t]
   [app.util.time :as dt]
   [buddy.sign.jwe :as jwe]
   [clojure.spec.alpha :as s]))

(s/def ::tokens-key bytes?)

(defn generate
  [{:keys [tokens-key]} claims]
  (us/assert! ::tokens-key tokens-key)
  (let [payload (-> claims
                    (assoc :iat (dt/now))
                    (d/without-nils)
                    (t/encode))]
    (jwe/encrypt payload tokens-key {:alg :a256kw :enc :a256gcm})))

(defn decode
  [{:keys [tokens-key]} token]
  (let [payload (jwe/decrypt token tokens-key {:alg :a256kw :enc :a256gcm})]
    (t/decode payload)))

(defn verify
  [sprops {:keys [token] :as params}]
  (let [claims (decode sprops token)]
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





