;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.storage
  (:require [uxbox.util.transit :as t]))

(enable-console-print!)

(defn- persist
  [alias value]
  (let [key (name value)
        value (t/encode value)]
    (.setItem js/localStorage key value)))

(defn- load
  [alias]
  (println "load" *target*)
  (if (= *target* "nodejs")
    {}
    (when-let [data (.getItem js/localStorage (name alias))]
      (try
        (t/decode data)
        (catch js/Error e
          (js/console.log (.-stack e))
          {})))))

(defn make-storage
  [alias]
  (let [data (atom (load alias))]
    (when (not= *target* "nodejs")
      (add-watch data :sub #(persist alias %4)))
    (reify
      ICounted
      (-count [_]
        (count @data))

      ISeqable
      (-seq [_]
        (seq @data))

      IReset
      (-reset! [self newval]
        (-reset! data newval))

      ISwap
      (-swap! [self f]
        (-swap! data f))
      (-swap! [self f x]
        (-swap! data f x))
      (-swap! [self f x y]
        (-swap! data f x y))
      (-swap! [self f x y more]
        (-swap! data f x y more))

      ILookup
      (-lookup [_ key]
        (-lookup @data key nil))
      (-lookup [_ key not-found]
        (-lookup @data key not-found)))))

(def storage (make-storage "uxbox"))
