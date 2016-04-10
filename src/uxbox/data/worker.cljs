;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.data.worker
  "Worker related api and initialization events."
  (:require [beicon.core :as rx]
            [uxbox.rstore :as rs]
            [uxbox.constants :as c]
            [uxbox.util.workers :as uw]))

(defonce worker (uw/init "/js/worker.js"))

;; --- Worker Initialization

(defrecord InitializeWorker [id]
  rs/EffectEvent
  (-apply-effect [_ state]
    (let [page (get-in state [:pages-by-id id])
          opts (:options page)
          message {:cmd :grid/init
                   :width c/viewport-width
                   :height c/viewport-height
                   :x-axis (:grid/x-axis opts c/grid-x-axis)
                   :y-axis (:grid/y-axis opts c/grid-y-axis)}]
      (uw/send! worker message))))

(defn initialize
  [id]
  (InitializeWorker. id))
