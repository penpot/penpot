;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.setup.keys
  "Keys derivation service."
  (:refer-clojure :exclude [derive])
  (:require
   [app.common.spec :as us]
   [buddy.core.kdf :as bk]))

(defn derive
  "Derive a key from secret-key"
  [secret-key & {:keys [salt size]}]
  (us/assert! ::us/not-empty-string secret-key)
  (let [engine (bk/engine {:key secret-key
                           :salt salt
                           :alg :hkdf
                           :digest :blake2b-512})]
    (bk/get-bytes engine size)))
