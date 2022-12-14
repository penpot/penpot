;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.json
  (:require
   [jsonista.core :as j]))

(defn mapper
  [params]
  (j/object-mapper params))

(defn read!
  ([from] (j/read-value from j/keyword-keys-object-mapper))
  ([from mapper] (j/read-value from mapper)))

(defn write!
  ([to v] (j/write-value to v j/keyword-keys-object-mapper))
  ([to v mapper] (j/write-value to v mapper)))

(defn encode
  ([v] (j/write-value-as-bytes v j/keyword-keys-object-mapper))
  ([v mapper] (j/write-value-as-bytes v mapper)))

(defn decode
  ([v] (j/read-value v j/keyword-keys-object-mapper))
  ([v mapper] (j/read-value v mapper)))

(defn encode-str
  ([v] (j/write-value-as-string v j/keyword-keys-object-mapper))
  ([v mapper] (j/write-value-as-string v mapper)))

