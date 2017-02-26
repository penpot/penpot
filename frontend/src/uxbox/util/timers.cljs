;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.timers
  (:require [beicon.core :as rx]))

(defn schedule
  [ms func]
  (let [sem (js/setTimeout #(func) ms)]
    (reify rx/ICancellable
      (-cancel [_]
        (js/clearTimeout sem)))))

(defn interval
  [ms func]
  (let [sem (js/setInterval #(func) ms)]
    (reify rx/ICancellable
      (-cancel [_]
        (js/clearInterval sem)))))
