;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.resize
  (:require [beicon.core :as rx]
            [uxbox.util.rstore :as rs]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.ui.core :as uuc]
            [uxbox.main.ui.workspace.base :as wb]
            [uxbox.main.geom.point :as gpt]))

(declare initialize)

;; --- Public Api

(defn watch-resize-actions
  []
  (as-> uuc/actions-s $
    (rx/dedupe $)
    (rx/filter #(= (:type %) "ui.shape.resize") $)
    (rx/on-value $ initialize)))

;; --- Implementation

(declare handle-resize)

(defn- initialize
  [event]
  (let [{:keys [vid shape] :as payload} (:payload event)
        stoper (->> uuc/actions-s
                    (rx/map :type)
                    (rx/filter #(empty? %))
                    (rx/take 1))
        stream (->> wb/mouse-delta-s
                    (rx/take-until stoper)
                    (rx/with-latest-from vector wb/mouse-ctrl-s))]
    (when @wb/alignment-ref
      (rs/emit! (uds/initial-vertext-align shape vid)))
    (rx/subscribe stream #(handle-resize shape vid %))))

(defn- handle-resize
  [shape vid [delta ctrl?]]
  (let [params {:vid vid :delta (assoc delta :lock ctrl?)}]
    (rs/emit! (uds/update-vertex-position shape params))))
