;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns sodi.pwhash
  "Password Hashing"
  (:refer-clojure :exclude [derive])
  (:require [clojure.string :as str]
            [sodi.prng :as rng]
            [sodi.util :as util])
  (:import javax.crypto.spec.PBEKeySpec
           javax.crypto.SecretKeyFactory))

;; NOTE: at this moment only pbkdf2+sha512 algorithm is implement is
;; good enough for start but we need seriosly consider add argon2
;; algorithm and set it as default (and probably force rehash all
;; passwords on login to start use new algorithm for existing users).
;;
;; Any help is welcome, lazysodium-java is already included in the
;; dependencies so this should be pretty straight forward implement
;; it.

(defmulti ^:no-doc derive-password :alg)
(defmulti ^:no-doc verify-password :alg)
(defmulti ^:no-doc format-password :alg)
(defmulti ^:no-doc parse-password
  (fn [password]
    (-> password
        (str/split #"\$" 2)
        (first))))

;; --- Impl

(defmethod parse-password :default
  [password]
  (let [[alg salt cc mc hash] (str/split password #"\$")]
    (when (some nil? [salt cc mc password])
      (throw (ex-info "Malformed hash" {:password password})))
    {:alg alg
     :salt (util/b64s->bytes salt)
     :hash (util/b64s->bytes hash)
     :cpucost (Integer/parseInt cc)
     :memcost (Integer/parseInt mc)}))

(defmethod derive-password "pbkdf2+sha512"
  [{:keys [alg password salt cpucost] :as options}]
  (let [salt (or salt (rng/random-bytes 16))
        cpucost (or cpucost 50000)
        pwd (.toCharArray ^String password)
        spec (PBEKeySpec. pwd salt cpucost 512)
        skf  (SecretKeyFactory/getInstance "PBKDF2WithHmacSHA256")
        hash (.getEncoded (.generateSecret skf spec))]
    {:alg alg
     :hash hash
     :salt salt
     :cpucost cpucost
     :memcost 0}))

(defmethod derive-password :default
  [options]
  (derive-password (assoc options :alg "pbkdf2+sha512")))

(defmethod format-password :default
  [{:keys [alg hash salt cpucost memcost]}]
  (let [salt (util/bytes->b64s salt)
        hash (util/bytes->b64s hash)]
    (format "%s$%s$%s$%s$%s" alg salt cpucost memcost hash)))

(defmethod verify-password :default
  [pw-params]
  (let [candidate (-> (assoc pw-params :password (:candidate pw-params))
                      (derive-password))]
    (util/equals? (:hash pw-params)
                  (:hash candidate))))

;; --- Public API

(defn derive
  ([password] (derive password {}))
  ([password options]
   (-> (assoc options :password password)
       (derive-password)
       (format-password))))

(defn verify
  ([candidate password] (verify candidate password {}))
  ([candidate password {:keys [alg setter-fn] :as options}]
   (when-not (and candidate password)
     (throw (ex-info "Invalid arguments." {})))
   (let [pw-params (-> (parse-password password)
                       (assoc :candidate candidate))
         pw-params (if alg
                     (assoc pw-params :alg alg)
                     pw-params)]
     {:valid (verify-password pw-params)
      :need-rehash false})))
