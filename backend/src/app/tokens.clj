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
   [app.common.time :as ct]
   [app.common.transit :as t]
   [app.setup :as-alias setup]
   [buddy.sign.jwe :as jwe]))

(defn generate
  ([cfg claims] (generate cfg claims nil))
  ([{:keys [::setup/props] :as cfg} claims header]
   (assert (contains? props :tokens-key) "expect props to have tokens-key")

   (let [tokens-key
         (get props :tokens-key)

         payload
         (-> claims
             (update :iat (fn [v] (or v (ct/now))))
             (d/without-nils)
             (t/encode))]

     (jwe/encrypt payload tokens-key {:alg :a256kw :enc :a256gcm :header header}))))

(defn decode-header
  [token]
  (ex/ignoring
   (jwe/decode-header token)))

(defn decode
  [{:keys [::setup/props] :as cfg} token]
  (let [tokens-key
        (get props :tokens-key)

        payload
        (jwe/decrypt token tokens-key {:alg :a256kw :enc :a256gcm})]

    (t/decode payload)))

(defn verify
  [cfg {:keys [token] :as params}]
  (let [claims (decode cfg token)]
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





