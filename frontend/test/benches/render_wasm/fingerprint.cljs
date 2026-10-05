;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.fingerprint
  "Scene fingerprints identify validated starting snapshots by content.
  Encoding version 1 orders unordered representations in production verbose
  Transit, preserves type tags and ordered collections, and hashes UTF-8 bytes.
  Callers validate snapshots and compute fingerprints before upload and timers."
  (:require
   [benches.render-wasm.codec :as codec]
   [goog.crypt :as crypt]
   [goog.crypt.Sha256]))

(declare canonicalize)

(defn- canonical-text
  "Encodes an already canonical Transit representation as deterministic text."
  [value]
  (js/JSON.stringify value))

(defn- canonical-array
  "Preserves array order while canonicalizing each element."
  [value]
  (into-array (map canonicalize (array-seq value))))

(defn- canonical-pairs
  "Orders a compound map by canonical key text, preserving key/value pairs."
  [value]
  (->> (partition 2 (array-seq value))
       (map (fn [[k v]] [(canonicalize k) (canonicalize v)]))
       (sort-by (comp canonical-text first))
       (mapcat identity)
       (into-array)))

(defn- canonicalize
  "Orders verbose Transit objects, compound maps and unordered sets.
  Tagged ordered-map and ordered-set representations retain their array order."
  [value]
  (cond
    (array? value)
    (canonical-array value)

    (and (some? value) (= "object" (goog/typeOf value)))
    (let [keys (sort (array-seq (js/Object.keys value)))
          out  (js/Object.create nil)]
      (doseq [k keys]
        (let [v (unchecked-get value k)]
          (aset out k
                (cond
                  (= k "~#cmap") (canonical-pairs v)
                  (= k "~#set") (->> (array-seq v)
                                     (map canonicalize)
                                     (sort-by canonical-text)
                                     (into-array))
                  :else (canonicalize v)))))
      out)

    :else value))

(defn snapshot-text
  "Returns encoding version 1 text for a validated snapshot.
  Realizing :objects removes ObjectsMap's serialized shape strings while keeping
  production shape, geometry and path tags in the values."
  [snapshot]
  (-> (assoc snapshot :objects (into {} (:objects snapshot)))
      (codec/encode-str {:type :json-verbose})
      (js/JSON.parse)
      (canonicalize)
      (canonical-text)))

(defn fingerprint
  "Returns a SHA-256 digest and encoding version for a validated snapshot."
  [snapshot]
  (let [hash (goog.crypt.Sha256.)]
    (.update hash (crypt/stringToUtf8ByteArray (snapshot-text snapshot)))
    {:encoding-version 1
     :algorithm :sha-256
     :digest (crypt/byteArrayToHex (.digest hash))}))
