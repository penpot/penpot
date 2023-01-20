;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.uuid
  (:refer-clojure :exclude [next uuid zero?])
  (:require
   #?(:clj [clojure.core :as c])
   #?(:cljs [app.common.uuid-impl :as impl])
   #?(:cljs [cljs.core :as c]))
  #?(:clj (:import
           app.common.UUIDv8
           java.util.UUID
           java.nio.ByteBuffer)))

(def zero #uuid "00000000-0000-0000-0000-000000000000")

(defn zero?
  [v]
  (= zero v))

(defn next
  []
  #?(:clj (UUIDv8/create)
     :cljs (impl/v8)))

(defn random
  "Alias for clj-uuid/v4."
  []
  #?(:clj (UUID/randomUUID)
     :cljs (impl/v4)))

(defn uuid
  "Parse string uuid representation into proper UUID instance."
  [s]
  #?(:clj (UUID/fromString s)
     :cljs (c/parse-uuid s)))

(defn custom
  ([a] #?(:clj (UUID. 0 a) :cljs (c/parse-uuid (impl/custom 0 a))))
  ([b a] #?(:clj (UUID. b a) :cljs (c/parse-uuid (impl/custom b a)))))

#?(:clj
   (defn get-word-high
     [id]
     (.getMostSignificantBits ^UUID id)))

#?(:clj
   (defn get-word-low
     [id]
     (.getLeastSignificantBits ^UUID id)))

#?(:clj
   (defn get-bytes
     [^UUID o]
     (let [buf (ByteBuffer/allocate 16)]
       (.putLong buf (.getMostSignificantBits o))
       (.putLong buf (.getLeastSignificantBits o))
       (.array buf))))

#?(:clj
   (defn from-bytes
     [^bytes o]
     (let [buf (ByteBuffer/wrap o)]
       (UUID. ^long (.getLong buf)
              ^long (.getLong buf)))))
