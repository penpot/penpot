;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.timers
  (:require [beicon.core :as rx]))

(defn schedule
  ([func]
   (schedule 0 func))
  ([ms func]
   (let [sem (js/setTimeout #(func) ms)]
     (reify rx/IDisposable
       (-dispose [_]
         (js/clearTimeout sem))))))

(defn interval
  [ms func]
  (let [sem (js/setInterval #(func) ms)]
    (reify rx/IDisposable
      (-dispose [_]
        (js/clearInterval sem)))))

(defn schedule-on-idle
  [func]
  (let [sem (js/requestIdleCallback #(func))]
    (reify rx/IDisposable
      (-dispose [_]
        (js/cancelIdleCallback sem)))))
