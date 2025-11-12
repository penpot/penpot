;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.storage
  (:require
   [app.common.exceptions :as ex]
   [app.common.time :as ct]
   [app.common.transit :as t]
   [app.util.functions :as fns]
   [app.util.globals :as g]
   [cuerdas.core :as str]
   [okulary.util :as ou]))

;; Using ex/ignoring because can receive a DOMException like this when
;; importing the code as a library: Failed to read the 'localStorage'
;; property from 'Window': Storage is disabled inside 'data:' URLs.
(defonce ^:private local-storage-backend
  (ex/ignoring (unchecked-get g/global "localStorage")))

(defonce ^:private session-storage-backend
  (ex/ignoring (unchecked-get g/global "sessionStorage")))

(def ^:dynamic *sync*
  "Dynamic variable which determines the mode of operation of the
  storage mutatio actions. By default is asynchronous."
  false)

(defn- encode-key
  [prefix k]
  (assert (keyword? k) "key must be keyword")
  (let [kns (namespace k)
        kn  (name k)]
    (str prefix ":" kns "/" kn)))

(defn- decode-key
  [prefix k]
  (when (str/starts-with? k prefix)
    (let [l (+ (count prefix) 1)
          k (subs k l)]
      (if (str/starts-with? k "/")
        (keyword (subs k 1))
        (let [[kns kn] (str/split k "/" 2)]
          (keyword kns kn))))))

(defn- lookup-by-index
  [backend prefix result index]
  (try
    (let [key  (.key ^js backend index)
          key' (decode-key prefix key)]
      (if key'
        (let [val (.getItem ^js backend key)]
          (assoc! result key' (t/decode-str val)))
        result))
    (catch :default _
      result)))

(defn- load-data
  [backend prefix]
  (if (some? backend)
    (let [length (.-length ^js backend)]
      (loop [index  0
             result (transient {})]
        (if (< index length)
          (recur (inc index)
                 (lookup-by-index backend prefix result index))
          (persistent! result))))
    {}))

(defn create-storage
  [backend prefix]
  (let [initial   (load-data backend prefix)
        curr-data #js {:content initial}
        last-data #js {:content initial}
        watches   (js/Map.)

        update-key
        (fn [key val]
          (when (some? backend)
            (if (some? val)
              (.setItem ^js backend (encode-key prefix key) (t/encode-str val {:with-meta true}))
              (.removeItem ^js backend (encode-key prefix key)))))

        on-change*
        (fn [curr-state]
          (let [prev-state (unchecked-get last-data "content")]
            (try
              (run! (fn [key]
                      (let [prev-val (get prev-state key)
                            curr-val (get curr-state key)]
                        (when-not (identical? curr-val prev-val)
                          (update-key key curr-val))))
                    (into #{} (concat (keys curr-state)
                                      (keys prev-state))))
              (finally
                (unchecked-set last-data "content" curr-state)))))

        on-change
        (fns/debounce on-change* 2000)]

    (reify
      IAtom

      IDeref
      (-deref [_] (unchecked-get curr-data "content"))

      ILookup
      (-lookup [coll k]
        (-lookup coll k nil))
      (-lookup [_ k not-found]
        (let [state (unchecked-get curr-data "content")]
          (-lookup state k not-found)))

      IReset
      (-reset! [self newval]
        (let [oldval (unchecked-get curr-data "content")]
          (unchecked-set curr-data "content" newval)
          (if *sync*
            (on-change* newval)
            (on-change newval))
          (when (> (.-size watches) 0)
            (-notify-watches self oldval newval))
          newval))

      ISwap
      (-swap! [self f]
        (let [state (unchecked-get curr-data "content")]
          (-reset! self (f state))))
      (-swap! [self f x]
        (let [state (unchecked-get curr-data "content")]
          (-reset! self (f state x))))
      (-swap! [self f x y]
        (let [state (unchecked-get curr-data "content")]
          (-reset! self (f state x y))))
      (-swap! [self f x y more]
        (let [state (unchecked-get curr-data "content")]
          (-reset! self (apply f state x y more))))

      IWatchable
      (-notify-watches [self oldval newval]
        (ou/doiter
         (.entries watches)
         (fn [n]
           (let [f (aget n 1)
                 k (aget n 0)]
             (f k self oldval newval)))))

      (-add-watch [self key f]
        (.set watches key f)
        self)

      (-remove-watch [_ key]
        (.delete watches key)))))

(defonce global  (create-storage local-storage-backend "penpot-global"))
(defonce user    (create-storage local-storage-backend "penpot-user"))
(defonce storage (create-storage local-storage-backend "penpot"))
(defonce session (create-storage session-storage-backend "penpot"))

(defonce before-unload
  (letfn [(on-before-unload [_]
            (binding [*sync* true]
              (swap! global assoc ::last-refresh (ct/now))
              (swap! user assoc ::last-refresh (ct/now))))]
    (.addEventListener g/window "beforeunload" on-before-unload)
    on-before-unload))
