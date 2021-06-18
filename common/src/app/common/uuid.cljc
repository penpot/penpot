;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.uuid
  (:refer-clojure :exclude [next uuid zero?])
  (:require
   #?(:clj [app.common.data :as d])
   #?(:clj [clj-uuid :as impl])
   #?(:clj [clojure.core :as c])
   #?(:cljs [app.common.uuid-impl :as impl])
   #?(:cljs [cljs.core :as c]))
  #?(:clj (:import java.util.UUID)))

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

(defn custom
  ([a] #?(:clj (UUID. 0 a) :cljs (c/uuid (impl/custom 0 a))))
  ([b a] #?(:clj (UUID. b a) :cljs (c/uuid (impl/custom b a)))))

#?(:clj
   (d/export impl/get-word-high))
