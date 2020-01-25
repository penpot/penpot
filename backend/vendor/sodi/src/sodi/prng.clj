;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns sodi.prng
  "Random data generation helpers."
  (:require [clojure.string :as str]
            [sodi.util :as util])
  (:import java.security.SecureRandom
           java.nio.ByteBuffer))

(defonce ^:no-doc rng (SecureRandom.))

(defn random-bytes
  "Generate a byte array of scpecified length with random
  bytes taken from secure random number generator.
  This method should be used to generate a random
  iv/salt or arbitrary length."
  ([^long numbytes]
   (let [buffer (byte-array numbytes)]
     (.nextBytes rng buffer)
     buffer))
  ([^SecureRandom rng ^long numbytes]
   (let [buffer (byte-array numbytes)]
     (.nextBytes rng buffer)
     buffer)))

(defn random-nonce
  "Generate a secure nonce based on current time
  and additional random data obtained from secure random
  generator. The minimum value is 8 bytes, and recommended
  minimum value is 32."
  [^long numbytes]
  (let [buffer (ByteBuffer/allocate numbytes)]
    (.putLong buffer (System/currentTimeMillis))
    (.put buffer (random-bytes (.remaining buffer)))
    (.array buffer)))
