;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.setup.keys
  "Keys derivation service."
  (:refer-clojure :exclude [derive])
  (:require
   [buddy.core.kdf :as bk]))

(defn derive
  "Derive a key from secret-key"
  [secret-key & {:keys [salt size] :or {size 32}}]
  (assert (string? secret-key) "expect string")
  (assert (seq secret-key) "expect string")
  (let [engine (bk/engine {:key secret-key
                           :salt salt
                           :alg :hkdf
                           :digest :blake2b-512})]
    (bk/get-bytes engine size)))
