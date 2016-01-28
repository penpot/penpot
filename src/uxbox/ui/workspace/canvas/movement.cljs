(ns uxbox.ui.workspace.canvas.movement
  (:require-macros [uxbox.util.syntax :refer [define-once]])
  (:require [beicon.core :as rx]
            [cats.labs.lens :as l]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.data.workspace :as dw]))

(define-once :movement-subscription
  (letfn [(on-value [delta]
            (let [pageid (get-in @st/state [:workspace :page])
                  selected (get-in @st/state [:workspace :selected])
                  shapes (->> (vals @wb/shapes-by-id)
                              (filter #(= (:page %) pageid))
                              (filter (comp selected :id)))]
              (doseq [{:keys [id group]} shapes]
                (rs/emit! (dw/move-shape id delta)))))

          (init []
            (as-> wb/interactions-b $
              (rx/filter #(not= % :shape/movement) $)
              (rx/take 1 $)
              (rx/take-until $ wb/mouse-delta-s)
              (rx/on-value $ on-value)))]

    (as-> wb/interactions-b $
      (rx/dedupe $)
      (rx/filter #(= :shape/movement %) $)
      (rx/on-value $ init))))
