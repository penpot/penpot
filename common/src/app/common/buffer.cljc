;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.buffer
  "A collection of helpers and macros for work with byte
  buffer (ByteBuffer on JVM and DataView on JS)."
  (:refer-clojure :exclude [clone])
  (:require
   [app.common.uuid :as uuid])
  #?(:cljs
     (:require-macros [app.common.buffer])
     :clj
     (:import [java.nio ByteBuffer ByteOrder])))

(defmacro read-byte
  [target offset]
  (if (:ns &env)
    `(.getInt8 ~target ~offset true)
    (let [target (with-meta target {:tag 'java.nio.ByteBuffer})]
      `(long (.get ~target (unchecked-int ~offset))))))

(defmacro read-unsigned-byte
  [target offset]
  (if (:ns &env)
    `(.getUint8 ~target ~offset true)
    (let [target (with-meta target {:tag 'java.nio.ByteBuffer})]
      `(bit-and (long (.get ~target (unchecked-int ~offset))) 0xff))))

(defmacro read-bool
  [target offset]
  (if (:ns &env)
    `(== 1 (.getInt8 ~target ~offset true))
    (let [target (with-meta target {:tag 'java.nio.ByteBuffer})]
      `(== 1 (.get ~target (unchecked-int ~offset))))))

(defmacro read-short
  [target offset]
  (if (:ns &env)
    `(.getInt16 ~target ~offset true)
    (let [target (with-meta target {:tag 'java.nio.ByteBuffer})]
      `(.getShort ~target (unchecked-int ~offset)))))

(defmacro read-int
  [target offset]
  (if (:ns &env)
    `(.getInt32 ~target ~offset true)
    (let [target (with-meta target {:tag 'java.nio.ByteBuffer})]
      `(long (.getInt ~target (unchecked-int ~offset))))))

(defmacro read-float
  [target offset]
  (if (:ns &env)
    `(.getFloat32 ~target ~offset true)
    (let [target (with-meta target {:tag 'java.nio.ByteBuffer})]
      `(double (.getFloat ~target (unchecked-int ~offset))))))

(defmacro read-uuid
  [target offset]
  (if (:ns &env)
    `(let [a# (.getUint32 ~target (+ ~offset 0) true)
           b# (.getUint32 ~target (+ ~offset 4) true)
           c# (.getUint32 ~target (+ ~offset 8) true)
           d# (.getUint32 ~target (+ ~offset 12) true)]
       (uuid/from-unsigned-parts a# b# c# d#))

    (let [target (with-meta target {:tag 'java.nio.ByteBuffer})]
      `(try
         (.order ~target ByteOrder/BIG_ENDIAN)
         (let [msb# (.getLong ~target (unchecked-int (+ ~offset 0)))
               lsb# (.getLong ~target (unchecked-int (+ ~offset 8)))]
           (java.util.UUID. (long msb#) (long lsb#)))
         (finally
           (.order ~target ByteOrder/LITTLE_ENDIAN))))))

(defmacro write-byte
  [target offset value]
  (if (:ns &env)
    `(.setInt8 ~target ~offset ~value true)
    (let [target (with-meta target {:tag 'java.nio.ByteBuffer})]
      `(.put ~target (unchecked-int ~offset) (unchecked-byte ~value)))))

(defmacro write-u8
  [target offset value]
  (if (:ns &env)
    `(.setUint8 ~target ~offset ~value true)
    (let [target (with-meta target {:tag 'java.nio.ByteBuffer})]
      `(.put ~target ~offset (unchecked-byte ~value)))))

(defmacro write-bool
  [target offset value]
  (if (:ns &env)
    `(.setInt8 ~target ~offset (if ~value 0x01 0x00) true)
    (let [target (with-meta target {:tag 'java.nio.ByteBuffer})]
      `(.put ~target (unchecked-int ~offset) (unchecked-byte (if ~value 0x01 0x00))))))

(defmacro write-short
  [target offset value]
  (if (:ns &env)
    `(.setInt16 ~target ~offset ~value true)
    (let [target (with-meta target {:tag 'java.nio.ByteBuffer})]
      `(.putShort ~target (unchecked-int ~offset) (unchecked-short ~value)))))

(defmacro write-int
  [target offset value]
  (if (:ns &env)
    `(.setInt32 ~target ~offset ~value true)
    (let [target (with-meta target {:tag 'java.nio.ByteBuffer})]
      `(.putInt ~target (unchecked-int ~offset) (unchecked-int ~value)))))

(defmacro write-u32
  [target offset value]
  (if (:ns &env)
    `(.setUint32 ~target ~offset ~value true)
    (let [target (with-meta target {:tag 'java.nio.ByteBuffer})]
      `(.putInt ~target ~offset (unchecked-int ~value)))))

(defmacro write-i32
  "Idiomatic alias for `write-int`"
  [target offset value]
  `(write-int ~target ~offset ~value))

(defmacro write-float
  [target offset value]
  (if (:ns &env)
    `(.setFloat32 ~target ~offset ~value true)
    (let [target (with-meta target {:tag 'java.nio.ByteBuffer})]
      `(.putFloat ~target (unchecked-int ~offset) (unchecked-float ~value)))))

(defmacro write-f32
  "Idiomatic alias for `write-float`."
  [target offset value]
  `(write-float ~target ~offset ~value))

(defmacro write-uuid
  [target offset value]
  (if (:ns &env)
    `(let [barray# (uuid/get-u32 ~value)]
       (.setUint32 ~target (+ ~offset 0)  (aget barray# 0) true)
       (.setUint32 ~target (+ ~offset 4)  (aget barray# 1) true)
       (.setUint32 ~target (+ ~offset 8)  (aget barray# 2) true)
       (.setUint32 ~target (+ ~offset 12) (aget barray# 3) true))

    (let [target (with-meta target {:tag 'java.nio.ByteBuffer})
          value  (with-meta value {:tag 'java.util.UUID})]
      `(try
         (.order ~target ByteOrder/BIG_ENDIAN)
         (.putLong ~target (unchecked-int (+ ~offset 0)) (.getMostSignificantBits ~value))
         (.putLong ~target (unchecked-int (+ ~offset 8)) (.getLeastSignificantBits ~value))
         (finally
           (.order ~target ByteOrder/LITTLE_ENDIAN))))))

(defn wrap
  [data]
  #?(:clj  (let [buffer (ByteBuffer/wrap ^bytes data)]
             (.order buffer ByteOrder/LITTLE_ENDIAN))
     :cljs (new js/DataView (.-buffer ^js data))))

(defn allocate
  [size]
  #?(:clj (let [buffer (ByteBuffer/allocate (int size))]
            (.order buffer ByteOrder/LITTLE_ENDIAN))
     :cljs (new js/DataView (new js/ArrayBuffer size))))

(defn clone
  [buffer]
  #?(:clj
     (let [src (.array ^ByteBuffer buffer)
           len (alength ^bytes src)
           dst (byte-array len)]
       (System/arraycopy src 0 dst 0 len)
       (let [buffer (ByteBuffer/wrap dst)]
         (.order buffer ByteOrder/LITTLE_ENDIAN)))
     :cljs
     (let [buffer'  (.-buffer ^js/DataView buffer)
           src-view (js/Uint32Array. buffer')
           dst-buff (js/ArrayBuffer. (.-byteLength buffer'))
           dst-view (js/Uint32Array. dst-buff)]
       (.set dst-view src-view)
       (js/DataView. dst-buff))))

(defn equals?
  [buffer-a buffer-b]
  #?(:clj
     (.equals ^ByteBuffer buffer-a
              ^ByteBuffer buffer-b)

     :cljs
     (let [buffer-a (.-buffer buffer-a)
           buffer-b (.-buffer buffer-b)]
       (if (= (.-byteLength buffer-a)
              (.-byteLength buffer-b))
         (let [cb (js/Uint32Array. buffer-a)
               ob (js/Uint32Array. buffer-b)
               sz (alength cb)]
           (loop [i 0]
             (if (< i sz)
               (if (== (aget ob i)
                       (aget cb i))
                 (recur (inc i))
                 false)
               true)))
         false))))

(defn buffer?
  [o]
  #?(:clj (instance? ByteBuffer o)
     :cljs (instance? js/DataView o)))
