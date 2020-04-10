;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.util.uuid
  (:refer-clojure :exclude [next])
  (:require [clj-uuid :as uuid])
  (:import java.util.UUID))

(def ^:const zero uuid/+null+)
(def ^:const oid uuid/+namespace-oid+)

(defmacro next
  []
  `(uuid/v1))

(defmacro random
  "Alias for clj-uuid/v4."
  []
  `(uuid/v4))

(defmacro namespaced
  [ns data]
  `(uuid/v5 ~ns ~data))

(defmacro str->uuid
  [s]
  `(UUID/fromString ~s))

(defn from-string
  "Parse string uuid representation into proper UUID instance."
  [s]
  (UUID/fromString s))

(defn custom
  ([a] (UUID. 0 a))
  ([b a] (UUID. b a)))
