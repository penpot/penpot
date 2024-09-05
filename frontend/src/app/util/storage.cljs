;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.storage
  (:require
   ["lodash/debounce" :as ldebounce]
   [app.common.exceptions :as ex]
   [app.common.transit :as t]
   [app.util.globals :as g]
   [cuerdas.core :as str]))

;; Using ex/ignoring because can receive a DOMException like this when
;; importing the code as a library: Failed to read the 'localStorage'
;; property from 'Window': Storage is disabled inside 'data:' URLs.
(defonce ^:private local-storage
  (ex/ignoring (unchecked-get g/global "localStorage")))

(defn- encode-key
  [k]
  (assert (keyword? k) "key must be keyword")
  (let [kns (namespace k)
        kn  (name k)]
    (str "penpot:" kns "/" kn)))

(defn- decode-key
  [k]
  (when (str/starts-with? k "penpot:")
    (let [k (subs k 7)]
      (if (str/starts-with? k "/")
        (keyword (subs k 1))
        (let [[kns kn] (str/split k "/" 2)]
          (keyword kns kn))))))

(defn- lookup-by-index
  [result index]
  (try
    (let [key  (.key ^js local-storage index)
          key' (decode-key key)]
      (if key'
        (let [val (.getItem ^js local-storage key)]
          (assoc! result key' (t/decode-str val)))
        result))
    (catch :default _
      result)))

(defn- load
  []
  (when (some? local-storage)
    (let [length (.-length ^js local-storage)]
      (loop [index  0
             result (transient {})]
        (if (< index length)
          (recur (inc index)
                 (lookup-by-index result index))
          (persistent! result))))))

(defonce ^:private latest-state (load))

(defn- on-change*
  [curr-state]
  (let [prev-state latest-state]
    (try
      (run! (fn [key]
              (let [prev-val (get prev-state key)
                    curr-val (get curr-state key)]
                (when-not (identical? curr-val prev-val)
                  (if (some? curr-val)
                    (.setItem ^js local-storage (encode-key key) (t/encode-str curr-val))
                    (.removeItem ^js local-storage (encode-key key))))))
            (into #{} (concat (keys curr-state)
                              (keys prev-state))))
      (finally
        (set! latest-state curr-state)))))

(defonce on-change
  (ldebounce on-change* 2000 #js {:leading false :trailing true}))


(defonce storage (atom latest-state))
(add-watch storage :persistence
           (fn [_ _ _ curr-state]
             (on-change curr-state)))
