;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

#_:clj-kondo/ignore
(ns app.common.uuid
  (:refer-clojure :exclude [next uuid zero? short])
  (:require
   #?(:clj [clojure.core :as c])
   #?(:cljs [app.common.uuid-impl :as impl])
   #?(:cljs [cljs.core :as c])
   [app.common.data.macros :as dm])
  #?(:clj (:import
           app.common.UUIDv8
           java.util.UUID
           java.nio.ByteBuffer)))

(defn uuid
  "Parse string uuid representation into proper UUID instance."
  [s]
  #?(:clj (UUID/fromString s)
     :cljs (c/uuid s)))

(defn next
  []
  #?(:clj (UUIDv8/create)
     :cljs (uuid (impl/v8))))

(defn random
  "Alias for clj-uuid/v4."
  []
  #?(:clj (UUID/randomUUID)
     :cljs (uuid (impl/v4))))

(defn custom
  ([a] #?(:clj (UUID. 0 a) :cljs (uuid (impl/custom 0 a))))
  ([b a] #?(:clj (UUID. b a) :cljs (uuid (impl/custom b a)))))

(def zero (uuid "00000000-0000-0000-0000-000000000000"))

(defn zero?
  [v]
  (= zero v))

#?(:clj
   (defn get-word-high
     [id]
     (.getMostSignificantBits ^UUID id)))

#?(:clj
   (defn get-word-low
     [id]
     (.getLeastSignificantBits ^UUID id)))

(defn get-bytes
  [^UUID o]
  #?(:clj
     (let [buf (ByteBuffer/allocate 16)]
       (.putLong buf (.getMostSignificantBits o))
       (.putLong buf (.getLeastSignificantBits o))
       (.array buf))
     :cljs
     (impl/getBytes (.-uuid o))))

(defn from-bytes
  [^bytes o]
  #?(:clj
     (let [buf (ByteBuffer/wrap o)]
       (UUID. ^long (.getLong buf)
              ^long (.getLong buf)))
     :cljs
     (uuid (impl/fromBytes o))))

#?(:cljs
   (defn uuid->short-id
     "Return a shorter string of a safe subset of bytes of an uuid encoded
     with base62. It is only safe to use with uuid v4 and penpot custom v8"
     [id]
     (impl/shortV8 (dm/str id))))

#?(:cljs
   (defn get-u32
     [this]
     (let [buffer (unchecked-get this "__u32_buffer")]
       (if (nil? buffer)
         (let [buffer (impl/getUnsignedInt32Array (.-uuid ^UUID this))]
           (unchecked-set this "__u32_buffer" buffer)
           buffer)
         buffer))))

#?(:clj
   (defn hash-int
     [id]
     (let [a (.getMostSignificantBits ^UUID id)
           b (.getLeastSignificantBits ^UUID id)]
       (+ (clojure.lang.Murmur3/hashLong a)
          (clojure.lang.Murmur3/hashLong b)))))
