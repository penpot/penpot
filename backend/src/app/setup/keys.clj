;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.setup.keys
  "Keys derivation service."
  (:require
   [app.common.spec :as us]
   [buddy.core.kdf :as bk]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

(s/def ::secret-key ::us/string)
(s/def ::props (s/keys :req-un [::secret-key]))

(defmethod ig/pre-init-spec :app.setup/keys [_]
  (s/keys :req-un [::props]))

(defmethod ig/init-key :app.setup/keys
  [_ {:keys [props] :as cfg}]
  (fn [& {:keys [salt _]}]
    (let [engine (bk/engine {:key (:secret-key props)
                             :salt salt
                             :alg :hkdf
                             :digest :blake2b-512})]
      (bk/get-bytes engine 32))))

