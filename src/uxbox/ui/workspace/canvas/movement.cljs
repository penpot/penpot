(ns uxbox.ui.workspace.canvas.movement
  (:require-macros [uxbox.util.syntax :refer [define-once]])
  (:require [beicon.core :as rx]
            [cats.labs.lens :as l]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.data.workspace :as dw]))

(define-once :mouse-subscriptions
  (as-> (rx/with-latest-from vector wb/interactions-b wb/mouse-delta-s) $
    (rx/filter #(= (:type (second %)) :shape/movement) $)
    (rx/map first $)
    (rx/on-value $ (fn [delta]
                     (let [pageid (get-in @st/state [:workspace :page])
                           selected (get-in @st/state [:workspace :selected])
                           shapes (->> (vals @wb/shapes-by-id)
                                       (filter #(= (:page %) pageid))
                                       (filter (comp selected :id)))]
                       (doseq [{:keys [id group]} shapes]
                         (rs/emit! (dw/move-shape id delta))))))))

