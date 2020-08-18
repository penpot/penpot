;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns app.util.storage
  (:require [app.util.transit :as t]))

(defn- ^boolean is-worker?
  []
  (or (= *target* "nodejs")
      (not (exists? js/window))))

(defn- persist
  [alias value]
  (when-not (is-worker?)
    (let [key (name alias)
          value (t/encode value)]
      (.setItem js/localStorage key value))))

(defn- load
  [alias]
  (when-not (is-worker?)
    (let [data (.getItem js/localStorage (name alias))]
      (try
        (t/decode data)
        (catch :default e
          (js/console.error "Error on loading data from local storage." e)
          nil)))))

(defn- make-storage
  [alias]
  (let [data (atom (load alias))]
    (add-watch data :sub #(persist alias %4))
    (reify
      Object
      (toString [_]
        (str "Storage" (pr-str @data)))

      ICounted
      (-count [_]
        (count @data))

      ISeqable
      (-seq [_]
        (seq @data))

      IReset
      (-reset! [self newval]
        (reset! data newval))

      ISwap
      (-swap! [self f]
        (swap! data f))
      (-swap! [self f x]
        (swap! data f x))
      (-swap! [self f x y]
        (swap! data f x y))
      (-swap! [self f x y more]
        (apply swap! data f x y more))

      ILookup
      (-lookup [_ key]
        (get @data key nil))
      (-lookup [_ key not-found]
        (get @data key not-found)))))

(def storage
  (make-storage "app"))
