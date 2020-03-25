;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs S.L

(ns uxbox.main.ui.components.chunked-list
  "A collection of general purpose utility components."
  (:require
   [beicon.core :as rx]
   [rumext.alpha :as mf]
   [uxbox.util.timers :refer [schedule-on-idle]]))

(mf/defc chunked-list
  [{:keys [items children initial-size chunk-size]
    :or {initial-size 30 chunk-size 5}
    :as props}]
  (letfn [(initial-state []
            (let [total (count items)
                  size (if (> total initial-size) initial-size total)
                  current (take size items)
                  pending (drop size items)]
              {:current (vec current)
               :pending pending
               :pending-num (- total size)}))

          (update-state [{:keys [current pending pending-num] :as state}]
            (let [chunk-size (if (> pending-num chunk-size) chunk-size pending-num)]
              {:current (into current (take chunk-size pending))
               :pending (drop chunk-size pending)
               :pending-num (- pending-num chunk-size)}))
          (after-render [state]
            (when (pos? (:pending-num @state))
              (let [sem (schedule-on-idle (fn [] (swap! state update-state)))]
                #(rx/cancel! sem))))]

    (let [initial (mf/use-memo initial-state)
          state   (mf/use-state initial)]
      (mf/use-effect nil #(after-render state))
      (for [item (:current @state)]
        (children item)))))
