;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns sodi.util
  "Password Hashing"
  (:require [clojure.string :as str])
  (:import java.util.Base64
           java.util.Base64$Encoder
           java.util.Base64$Decoder))

(defn str->bytes
  "Convert string to byte array."
  ([^String s]
   (str->bytes s "UTF-8"))
  ([^String s, ^String encoding]
   (.getBytes s encoding)))

(defn bytes->str
  "Convert byte array to String."
  ([^bytes data]
   (bytes->str data "UTF-8"))
  ([^bytes data, ^String encoding]
   (String. data encoding)))

(defn bytes->b64s
  [^bytes data]
  (let [^Base64$Encoder encoder (-> (Base64/getUrlEncoder)
                                    (.withoutPadding))]
    (.encodeToString encoder data)))

(defn b64s->bytes
  [^String data]
  (let [^Base64$Decoder decoder (Base64/getUrlDecoder)]
    (.decode decoder data)))

(defn equals?
  "Test whether two sequences of characters or bytes are equal in a way that
  protects against timing attacks. Note that this does not prevent an attacker
  from discovering the *length* of the data being compared."
  [a b]
  (let [a (map int a), b (map int b)]
    (if (and a b (= (count a) (count b)))
      (zero? (reduce bit-or 0 (map bit-xor a b)))
      false)))
