;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui.workspace.canvas.movement
  (:require-macros [uxbox.util.syntax :refer [define-once]])
  (:require [beicon.core :as rx]
            [lentes.core :as l]
            [uxbox.rstore :as rs]
            [uxbox.state :as ust]
            [uxbox.ui.core :as uuc]
            [uxbox.ui.workspace.base :as uuwb]
            [uxbox.data.shapes :as uds]))

(define-once :movement-subscription
  (letfn [(on-value [delta]
            (let [pageid (get-in @ust/state [:workspace :page])
                  selected (get-in @ust/state [:workspace :selected])
                  shapes (->> (vals @uuwb/shapes-by-id-l)
                              (filter #(= (:page %) pageid))
                              (filter (comp selected :id)))]
              (doseq [{:keys [id group]} shapes]
                (rs/emit! (uds/move-shape id delta)))))

          (init []
            (let [stoper (->> uuc/actions-s
                              (rx/map :type)
                              (rx/filter empty?)
                              (rx/take 1))]
              (as-> uuwb/mouse-delta-s $
                (rx/take-until stoper $)
                (rx/on-value $ on-value))))]

    (as-> uuc/actions-s $
      (rx/filter #(= "ui.shape.move" (:type %)) $)
      (rx/on-value $ init))))
