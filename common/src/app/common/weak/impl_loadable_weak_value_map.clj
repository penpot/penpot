;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.weak.impl-loadable-weak-value-map
  (:import
   java.lang.ref.SoftReference
   java.util.HashMap))

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

  clojure.lang.Counted
  (count [_]
    (.size data))

  clojure.lang.Seqable
  (seq [this]
    (->> (seq (.keySet data))
         (map (fn [key]
                (clojure.lang.MapEntry. key (.valAt this key)))))))

(defn loadable-weak-value-map
  [keys load-fn preload-data]
  (let [^HashMap hmap (HashMap. (count keys))]
    (run! (fn [key]
            (if-let [value (get preload-data key)]
              (.put hmap key value)
              (.put hmap key (SoftReference. nil))))
          keys)
    (LoadableWeakValueMap. hmap load-fn)))
