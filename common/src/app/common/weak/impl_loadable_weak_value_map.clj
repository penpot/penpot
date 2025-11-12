;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.weak.impl-loadable-weak-value-map
  (:import
   clojure.lang.Associative
   clojure.lang.Counted
   clojure.lang.IPersistentVector
   clojure.lang.MapEntry
   clojure.lang.Seqable
   java.lang.ref.SoftReference
   java.util.HashMap
   java.util.Map$Entry))

(deftype LoadableWeakValueMap [^HashMap data load-fn]
  clojure.lang.ILookup
  (valAt [_ key]
    (when-let [reference (.get data key)]
      (if (instance? SoftReference reference)
        (or (.get ^SoftReference reference)
            (let [value (load-fn key)]
              (.put data key (SoftReference. value))
              value))
        reference)))

  (valAt [_ key default]
    (if-let [reference (.get data key)]
      (if (instance? SoftReference reference)
        (or (.get ^SoftReference reference)
            (let [value (load-fn key)]
              (.put data key (SoftReference. value))
              value))
        reference)
      default))

  Associative
  (containsKey [_ key]
    (.containsKey data key))

  (entryAt [_ key]
    (when-let [reference (.get data key)]
      (if (instance? SoftReference reference)
        (let [val (or (.get ^SoftReference reference)
                      (let [value (load-fn key)]
                        (.put data key (SoftReference. value))
                        value))]
          (MapEntry/create key val))
        (MapEntry/create key reference))))

  (assoc [_ key val]
    (let [data' (HashMap. data)]
      (.put data' key (SoftReference. val))
      (LoadableWeakValueMap. data' load-fn)))

  (cons [this other]
    (cond
      (instance? Map$Entry other)
      (.assoc ^Associative this
              (.getKey ^Map$Entry other)
              (.getValue ^Map$Entry other))

      (vector? other)
      (do
        (when (not= 2 (count other))
          (throw (IllegalArgumentException. "Vector arg to map conj must be a pair")))
        (.assoc ^Associative this
                (.nth ^IPersistentVector other 0)
                (.nth ^IPersistentVector other 1)))

      :else
      (throw (IllegalArgumentException. "only MapEntry or Vectors supported on cons"))))

  (empty [_]
    (LoadableWeakValueMap. (HashMap.) load-fn))

  (equiv [_ _other]
    (throw (UnsupportedOperationException. "equiv not implemented")))

  Counted
  (count [_]
    (.size data))

  Seqable
  (seq [this]
    (->> (seq (.keySet data))
         (map (fn [key]
                (MapEntry/create key (.valAt this key)))))))

(defn loadable-weak-value-map
  [keys load-fn preload-data]
  (let [^HashMap hmap (HashMap. (count keys))]
    (run! (fn [key]
            (if-let [value (get preload-data key)]
              (.put hmap key value)
              (.put hmap key (SoftReference. nil))))
          keys)
    (LoadableWeakValueMap. hmap load-fn)))
