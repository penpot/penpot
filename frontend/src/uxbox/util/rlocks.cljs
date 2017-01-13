;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.rlocks
  "Reactive locks abstraction.

  Mainly used for lock the interface to do one concrete user action
  such can be draw new shape, scroll, move shape, etc, and avoid
  that other posible actions interfere in the locked one."
  (:require [beicon.core :as rx]))

(defonce lock (atom ::none))
(defonce stream (rx/subject))

(defn acquire!
  ([type]
   (when (= @lock ::none)
     (reset! lock type)
     (rx/push! stream [type nil])))
  ([type payload]
   (when (= @lock ::none)
     (reset! lock type)
     (rx/push! stream [type payload]))))

(defn release!
  [type]
  (when (= @lock type)
    (reset! lock ::none)
    (rx/push! stream [::none nil])))

