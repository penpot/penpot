;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns app.util.storage
  (:require
   [app.util.transit :as t]
   [app.util.timers :as tm]
   [app.common.exceptions :as ex]))

(defn- ^boolean is-worker?
  []
  (or (= *target* "nodejs")
      (not (exists? js/window))))

(defn- decode
  [v]
  (ex/ignoring (t/decode v)))

(def local
  {:get #(decode (.getItem ^js js/localStorage (name %)))
   :set #(.setItem ^js js/localStorage (name %1) (t/encode %2))})

(def session
  {:get #(decode (.getItem ^js js/sessionStorage (name %)))
   :set #(.setItem ^js js/sessionStorage (name %1) (t/encode %2))})

(defn- persist
  [alias storage value]
  (when-not (is-worker?)
    (tm/schedule-on-idle
     (fn [] ((:set storage) alias value)))))

(defn- load
  [alias storage]
  (when-not (is-worker?)
    ((:get storage) alias)))

(defn- make-storage
  [alias storage]
  (let [data (atom (load alias storage))]
    (add-watch data :sub #(persist alias storage %4))
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


(defonce storage
  (make-storage "app" local))

(defonce cache
  (make-storage "cache" session))
