;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.json
  (:refer-clojure :exclude [read])
  (:require
   [jsonista.core :as j]))

(defn encode-str
  [v]
  (j/write-value-as-string v j/keyword-keys-object-mapper))

(defn encode
  [v]
  (j/write-value-as-bytes v j/keyword-keys-object-mapper))

(defn decode-str
  [v]
  (j/read-value v j/keyword-keys-object-mapper))

(defn decode
  [v]
  (j/read-value v j/keyword-keys-object-mapper))

(defn read
  [v]
  (j/read-value v j/keyword-keys-object-mapper))
