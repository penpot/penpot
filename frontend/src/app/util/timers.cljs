;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.timers
  (:require
   [app.common.data :as d]
   [beicon.v2.core :as rx]
   [promesa.core :as p]))

(defn schedule
  ([func]
   (schedule 0 func))
  ([ms func]
   (let [sem (js/setTimeout #(func) ms)]
     (reify
       d/ICloseable
       (close! [_]
         (js/clearTimeout sem))

       rx/IDisposable
       (-dispose [_]
         (js/clearTimeout sem))))))

(defn dispose!
  [v]
  (rx/dispose! v))

(defn asap
  [f]
  (-> (p/resolved nil)
      (p/then (fn [_] (f)))))

(defn interval
  [ms func]
  (let [sem (js/setInterval #(func) ms)]
    (reify rx/IDisposable
      (-dispose [_]
        (js/clearInterval sem)))))

(if (and (exists? js/window)
         (.-requestIdleCallback js/window))
  (do
    (def ^:private request-idle-callback #(js/requestIdleCallback % #js {:timeout 30000})) ;; 30s timeout
    (def ^:private cancel-idle-callback #(js/cancelIdleCallback %)))
  (do
    (def ^:private request-idle-callback #(js/setTimeout % 250))
    (def ^:private cancel-idle-callback #(js/clearTimeout %))))

(defn schedule-on-idle
  ([ms func]
   ;; Schedule on idle after `ms` time
   (schedule ms #(schedule-on-idle func)))

  ([func]
   (let [sem (request-idle-callback #(func))]
     (reify rx/IDisposable
       (-dispose [_]
         (cancel-idle-callback sem))))))

(def ^:private request-animation-frame
  (if (and (exists? js/globalThis)
           (exists? (.-requestAnimationFrame js/globalThis)))
    #(.requestAnimationFrame js/globalThis %)
    #(js/setTimeout % 16)))

(defn raf
  [f]
  (^function request-animation-frame f))

(defn idle-then-raf
  [f]
  (schedule-on-idle #(^function raf f)))

