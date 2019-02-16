;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.token
  "Facilities for generate random tokens."
  (:require [buddy.core.nonce :as nonce]
            [buddy.core.hash :as hash]
            [buddy.core.codecs :as codecs]
            [buddy.core.codecs.base64 :as b64]))

(defn random
  "Returns a 32 bytes randomly generated token
  with 1024 random seed. The output is encoded
  using urlsafe variant of base64."
  []
  (-> (nonce/random-bytes 1024)
      (hash/blake2b-512)
      (b64/encode true)
      (codecs/bytes->str)))


