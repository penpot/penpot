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
  "Creates an UUID instance from string, expectes valid uuid strings,
  the existense of validation is implementation detail"
  [s]
  #?(:clj (UUID/fromString s)
     :cljs (c/uuid s)))

(defn parse
  "Parse string uuid representation into proper UUID instance, validates input"
  [s]
  #?(:clj (UUID/fromString s)
     :cljs (c/parse-uuid s)))

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

(defn get-word-high
  [id]
  #?(:clj (.getMostSignificantBits ^UUID id)
     :cljs (impl/getHi (.-uuid ^UUID id))))

(defn get-word-low
  [id]
  #?(:clj (.getLeastSignificantBits ^UUID id)
     :cljs (impl/getLo (.-uuid ^UUID id))))

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
   (defn get-unsigned-parts
     "Get a Uint32 array of length 4 that represents the UUID, needed
     for interact with wasm"
     [this]
     (impl/getUnsignedParts (.-uuid ^UUID this))))


#?(:cljs
   (defn get-u32
     "A cached variant of get-unsigned-parts"
     [this]
     (let [buffer (unchecked-get this "__u32_buffer")]
       (if (nil? buffer)
         (let [buffer (get-unsigned-parts this)]
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

;; Commented code used for debug
;; #?(:cljs
;;    (defn ^:export test-uuid
;;      []
;;      (let [expected #uuid "a1a2a3a4-b1b2-c1c2-d1d2-d3d4d5d6d7d8"]
;;
;;        (js/console.log "===> to-from-bytes-roundtrip")
;;        (js/console.log (uuid.impl/getBytes (str expected)))
;;        (js/console.log (uuid.impl/fromBytes (uuid.impl/getBytes (str expected))))
;;
;;        (js/console.log "===> HI LO roundtrip")
;;        (let [hi  (uuid.impl/getHi (str expected))
;;              lo  (uuid.impl/getLo (str expected))
;;              res (uuid.impl/custom hi lo)]
;;
;;          (js/console.log "HI:" hi)
;;          (js/console.log "LO:" lo)
;;          (js/console.log "RS:" res))
;;
;;        (js/console.log "===> OTHER")
;;        (let [parts (uuid.impl/getUnsignedParts (str expected))
;;              res   (uuid.impl/fromUnsignedParts (aget parts 0)
;;                                                 (aget parts 1)
;;                                                 (aget parts 2)
;;                                                 (aget parts 3))]
;;          (js/console.log "PARTS:" parts)
;;          (js/console.log "RES:  " res))
;;
;;        )))
