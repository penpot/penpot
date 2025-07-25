;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.tokens
  "Tokens generation API."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.time :as ct]
   [app.common.transit :as t]
   [buddy.sign.jwe :as jwe]))

(defn generate
  [{:keys [tokens-key]} claims]

  (dm/assert!
   "expexted token-key to be bytes instance"
   (bytes? tokens-key))

  (let [payload (-> claims
                    (assoc :iat (ct/now))
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
    (when (and (ct/inst? (:exp claims))
               (ct/is-before? (:exp claims) (ct/now)))
      (ex/raise :type :validation
                :code :invalid-token
                :reason :token-expired
                :params params))
    (when (and (contains? params :iss)
               (not= (:iss claims)
                     (:iss params)))
      (ex/raise :type :validation
                :code :invalid-token
                :reason :invalid-issuer
                :params params))
    claims))





