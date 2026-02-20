(ns app.main.data.nitrate
  (:require
   [app.main.data.modal :as modal]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn show-nitrate-popup
  []
  (ptk/reify ::show-nitrate-popup
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! ::get-nitrate-connectivity {})
           (rx/map (fn [connectivity]
                     (modal/show :nitrate-form (or connectivity {}))))))))
