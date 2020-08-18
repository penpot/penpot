;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.common.uuid
  (:refer-clojure :exclude [next uuid zero?])
  #?(:clj (:import java.util.UUID))
  #?(:clj
     (:require [clj-uuid :as impl]
               [clojure.core :as c])
     :cljs
     (:require [app.common.uuid-impl :as impl]
               [cljs.core :as c])))

(def zero #uuid "00000000-0000-0000-0000-000000000000")

(defn zero?
  [v]
  (= zero v))

(defn next
  []
  #?(:clj (impl/v1)
     :cljs (impl/v1)))

(defn random
  "Alias for clj-uuid/v4."
  []
  #?(:clj (impl/v4)
     :cljs (impl/v4)))

#?(:clj
   (defn namespaced
     [ns data]
     (impl/v5 ns data)))

(defn uuid
  "Parse string uuid representation into proper UUID instance."
  [s]
  #?(:clj (UUID/fromString s)
     :cljs (c/uuid s)))

#?(:clj
   (defn custom
     ([a] (UUID. 0 a))
     ([b a] (UUID. b a))))
