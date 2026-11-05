;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.setup.keys
  "Keys derivation service."
  (:refer-clojure :exclude [derive])
  (:require
   [buddy.core.codecs :as bc]
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

(defn derive-exporter-key
  "The management key of the exporter that renders for `tenant`: HKDF
  blake2b512 over the salt `exporter:<tenant>`, base64url.

  The single definition of the exporter key on the backend: the shared-key
  registry (bare `exporter` id, own tenant) and the per-tenant ids of the
  middleware both come through here. The Node worker derives the same
  value; both sides pin the same golden vector, so a change on one side
  fails a test instead of a deployment."
  [secret-key tenant]
  (assert (string? tenant) "expect string tenant")
  (-> (derive secret-key :salt (str "exporter:" tenant))
      (bc/bytes->b64-str true)))
