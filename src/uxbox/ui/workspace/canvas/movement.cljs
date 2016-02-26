(ns uxbox.ui.workspace.canvas.movement
  (:require-macros [uxbox.util.syntax :refer [define-once]])
  (:require [beicon.core :as rx]
            [cats.labs.lens :as l]
            [uxbox.rstore :as rs]
            [uxbox.state :as ust]
            [uxbox.ui.core :as uuc]
            [uxbox.ui.workspace.base :as uuwb]
            [uxbox.data.workspace :as dw]))

(define-once :movement-subscription
  (letfn [(on-value [delta]
            (let [pageid (get-in @ust/state [:workspace :page])
                  selected (get-in @ust/state [:workspace :selected])
                  shapes (->> (vals @uuwb/shapes-by-id-l)
                              (filter #(= (:page %) pageid))
                              (filter (comp selected :id)))]
              (doseq [{:keys [id group]} shapes]
                (rs/emit! (dw/move-shape id delta)))))

          (init []
            (as-> uuc/actions-s $
              (rx/filter #(not= % :shape/movement) $)
              (rx/take 1 $)
              (rx/take-until $ uuwb/mouse-delta-s)
              (rx/on-value $ on-value)))]

    (as-> uuc/actions-s $
      (rx/dedupe $)
      (rx/filter #(= :shape/movement %) $)
      (rx/on-value $ init))))
